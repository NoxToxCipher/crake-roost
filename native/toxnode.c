/* toxnode implementation. See toxnode.h.
 *
 * The setup sequence mirrors tox-bootstrapd's main() exactly, minus the daemon
 * shell: networking -> mono_time -> DHT -> forwarding -> announcements ->
 * group announces -> onion -> onion announce -> (MOTD) -> keys -> TCP relay.
 * The run loop is do_dht + do_tcp_server + networking_poll, same as the daemon.
 */
#include "toxnode.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../../tox-client/c-toxcore/toxcore/DHT.h"
#include "../../tox-client/c-toxcore/toxcore/TCP_server.h"
#include "../../tox-client/c-toxcore/toxcore/announce.h"
#include "../../tox-client/c-toxcore/toxcore/crypto_core.h"
#include "../../tox-client/c-toxcore/toxcore/forwarding.h"
#include "../../tox-client/c-toxcore/toxcore/group_announce.h"
#include "../../tox-client/c-toxcore/toxcore/group_onion_announce.h"
#include "../../tox-client/c-toxcore/toxcore/logger.h"
#include "../../tox-client/c-toxcore/toxcore/mono_time.h"
#include "../../tox-client/c-toxcore/toxcore/net_profile.h"
#include "../../tox-client/c-toxcore/toxcore/network.h"
#include "../../tox-client/c-toxcore/toxcore/onion.h"
#include "../../tox-client/c-toxcore/toxcore/onion_announce.h"
#include "../../tox-client/c-toxcore/toxcore/os_memory.h"
#include "../../tox-client/c-toxcore/toxcore/os_network.h"
#include "../../tox-client/c-toxcore/toxcore/os_random.h"
#include "../../tox-client/c-toxcore/other/bootstrap_node_packets.h"

/* Our own version stamp for the bootstrap/MOTD broadcast. */
#define TOXNODE_VERSION 1000000000UL

struct ToxNode {
    const Memory *mem;
    const Random *rng;
    const Network *ns;
    Logger *logger;
    Networking_Core *net;
    Mono_Time *mono_time;
    DHT *dht;
    Forwarding *forwarding;
    Announcements *announce;
    GC_Announces_List *group_announce;
    Onion *onion;
    Onion_Announce *onion_a;
    TCP_Server *tcp_server;
};

/* Load the DHT keypair from disk, or generate one and persist it. A stable key
 * is what makes this a recognisable bootstrap node rather than an ephemeral
 * client. Mirrors tox-bootstrapd's manage_keys(). */
static bool manage_keys(DHT *dht, const char *keys_path)
{
    enum { KEYS_SIZE = CRYPTO_PUBLIC_KEY_SIZE + CRYPTO_SECRET_KEY_SIZE };
    uint8_t keys[KEYS_SIZE];

    FILE *keys_file = fopen(keys_path, "rb");

    if (keys_file != NULL) {
        const size_t read_size = fread(keys, sizeof(uint8_t), KEYS_SIZE, keys_file);
        fclose(keys_file);

        if (read_size != KEYS_SIZE) {
            return false;
        }

        dht_set_self_public_key(dht, keys);
        dht_set_self_secret_key(dht, keys + CRYPTO_PUBLIC_KEY_SIZE);
        return true;
    }

    memcpy(keys, dht_get_self_public_key(dht), CRYPTO_PUBLIC_KEY_SIZE);
    memcpy(keys + CRYPTO_PUBLIC_KEY_SIZE, dht_get_self_secret_key(dht), CRYPTO_SECRET_KEY_SIZE);

    keys_file = fopen(keys_path, "wb");

    if (keys_file == NULL) {
        return false;
    }

    const size_t write_size = fwrite(keys, sizeof(uint8_t), KEYS_SIZE, keys_file);
    fclose(keys_file);
    return write_size == KEYS_SIZE;
}

static bool hex_to_bytes(const char *hex, uint8_t *out, size_t out_len)
{
    if (strlen(hex) != out_len * 2) {
        return false;
    }

    for (size_t i = 0; i < out_len; i++) {
        unsigned int byte = 0;

        if (sscanf(hex + i * 2, "%2x", &byte) != 1) {
            return false;
        }

        out[i] = (uint8_t)byte;
    }

    return true;
}

ToxNode *toxnode_start(const ToxNodeConfig *cfg)
{
    if (cfg == NULL || cfg->keys_path == NULL) {
        return NULL;
    }

    ToxNode *node = (ToxNode *)calloc(1, sizeof(ToxNode));

    if (node == NULL) {
        return NULL;
    }

    node->mem = os_memory();
    node->rng = os_random();
    node->ns = os_network();

    if (node->mem == NULL || node->rng == NULL || node->ns == NULL) {
        toxnode_stop(node);
        return NULL;
    }

    node->logger = logger_new(node->mem);

    IP ip;
    bool ipv6 = cfg->enable_ipv6;
    ip_init(&ip, ipv6);

    node->net = new_networking_ex(node->logger, node->mem, node->ns, &ip,
                                  cfg->dht_port, cfg->dht_port, NULL);

    if (node->net == NULL && ipv6 && cfg->enable_ipv4_fallback) {
        ipv6 = false;
        ip_init(&ip, ipv6);
        node->net = new_networking_ex(node->logger, node->mem, node->ns, &ip,
                                      cfg->dht_port, cfg->dht_port, NULL);
    }

    if (node->net == NULL) {
        toxnode_stop(node);
        return NULL;
    }

    node->mono_time = mono_time_new(node->mem, NULL, NULL);

    if (node->mono_time == NULL) {
        toxnode_stop(node);
        return NULL;
    }

    mono_time_update(node->mono_time);

    node->dht = new_dht(node->logger, node->mem, node->rng, node->ns, node->mono_time,
                        node->net, true, cfg->enable_lan_discovery);

    if (node->dht == NULL) {
        toxnode_stop(node);
        return NULL;
    }

    node->forwarding = new_forwarding(node->logger, node->mem, node->rng, node->mono_time,
                                      node->dht, node->net);
    node->announce = node->forwarding == NULL ? NULL
                     : new_announcements(node->logger, node->mem, node->rng, node->mono_time,
                                         node->forwarding, node->dht, node->net);
    node->group_announce = new_gca_list(node->mem);
    node->onion = new_onion(node->logger, node->mem, node->mono_time, node->rng,
                            node->dht, node->net);
    node->onion_a = new_onion_announce(node->logger, node->mem, node->rng, node->mono_time,
                                       node->dht, node->net);

    if (node->forwarding == NULL || node->announce == NULL || node->group_announce == NULL
            || node->onion == NULL || node->onion_a == NULL) {
        toxnode_stop(node);
        return NULL;
    }

    gca_onion_init(node->group_announce, node->onion_a);

    if (cfg->motd != NULL) {
        bootstrap_set_callbacks(node->net, TOXNODE_VERSION,
                                (const uint8_t *)cfg->motd, (uint16_t)(strlen(cfg->motd) + 1));
    }

    if (!manage_keys(node->dht, cfg->keys_path)) {
        toxnode_stop(node);
        return NULL;
    }

    if (cfg->enable_tcp_relay && cfg->tcp_port_count > 0) {
        node->tcp_server = new_tcp_server(node->logger, node->mem, node->rng, node->ns,
                                          cfg->enable_ipv6, cfg->tcp_port_count, cfg->tcp_ports,
                                          dht_get_self_secret_key(node->dht),
                                          node->onion, node->forwarding);

        if (node->tcp_server == NULL) {
            toxnode_stop(node);
            return NULL;
        }
    }

    return node;
}

bool toxnode_bootstrap(ToxNode *node, const char *host, uint16_t port, const char *public_key_hex)
{
    if (node == NULL || node->dht == NULL) {
        return false;
    }

    uint8_t key[CRYPTO_PUBLIC_KEY_SIZE];

    if (!hex_to_bytes(public_key_hex, key, sizeof(key))) {
        return false;
    }

    return dht_bootstrap_from_address(node->dht, host, node->net != NULL, false,
                                      net_htons(port), key);
}

uint32_t toxnode_iterate(ToxNode *node)
{
    if (node == NULL) {
        return 1000;
    }

    mono_time_update(node->mono_time);
    do_dht(node->dht);

    if (node->tcp_server != NULL) {
        do_tcp_server(node->tcp_server, node->mono_time);
    }

    networking_poll(node->net, NULL);

    return 30; /* the daemon polls on a similar short cadence */
}

void toxnode_self_public_key_hex(ToxNode *node, char *out)
{
    if (node == NULL || node->dht == NULL || out == NULL) {
        if (out != NULL) {
            out[0] = '\0';
        }

        return;
    }

    const uint8_t *pk = dht_get_self_public_key(node->dht);
    int at = 0;

    for (size_t i = 0; i < CRYPTO_PUBLIC_KEY_SIZE; i++) {
        at += snprintf(out + at, 65 - at, "%02X", pk[i]);
    }
}

uint64_t toxnode_incoming_packets(ToxNode *node)
{
    if (node == NULL || node->tcp_server == NULL) {
        return UINT64_MAX;
    }

    const Net_Profile *profile = tcp_server_get_net_profile(node->tcp_server);

    if (profile == NULL) {
        return UINT64_MAX;
    }

    return netprof_get_packet_count_total(profile, PACKET_DIRECTION_RECV);
}

uint64_t toxnode_outgoing_packets(ToxNode *node)
{
    if (node == NULL || node->tcp_server == NULL) {
        return UINT64_MAX;
    }

    const Net_Profile *profile = tcp_server_get_net_profile(node->tcp_server);

    if (profile == NULL) {
        return UINT64_MAX;
    }

    return netprof_get_packet_count_total(profile, PACKET_DIRECTION_SENT);
}

void toxnode_stop(ToxNode *node)
{
    if (node == NULL) {
        return;
    }

    kill_tcp_server(node->tcp_server);
    kill_onion_announce(node->onion_a);
    kill_gca(node->group_announce);
    kill_onion(node->onion);
    kill_announcements(node->announce);
    kill_forwarding(node->forwarding);
    kill_dht(node->dht);

    if (node->mono_time != NULL) {
        mono_time_free(node->mem, node->mono_time);
    }

    kill_networking(node->net);
    logger_kill(node->logger);
    free(node);
}
