/* toxnode: a minimal Tox bootstrap + UDP DHT + TCP relay node.
 *
 * This is the node core that tox-bootstrapd wraps, lifted out of its Unix-daemon
 * shell (no libconfig, no daemon(), no syslog, no privilege dropping) so it can
 * be driven from an Android foreground service via JNI. Configuration is passed
 * as arguments; the node persists only its DHT keypair, to a path the caller
 * chooses.
 *
 * A node built this way is public infrastructure: it holds no user secrets, so
 * using toxcore's own node code (rather than reimplementing it) is the correct
 * engineering choice, unlike the hardened client.
 */
#ifndef TOXNODE_H
#define TOXNODE_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ToxNode ToxNode;

typedef struct {
    uint16_t dht_port;          /* UDP DHT port, e.g. 33445 */
    bool enable_ipv6;
    bool enable_ipv4_fallback;  /* if IPv6 bind fails, retry IPv4-only */
    bool enable_lan_discovery;  /* false for a public node with no LAN peers */
    bool enable_tcp_relay;      /* the part that serves Tor / UDP-blocked users */
    const uint16_t *tcp_ports;  /* e.g. {443, 3389, 33445}; 443 matters most */
    uint16_t tcp_port_count;
    const char *keys_path;      /* where the persistent DHT keypair lives */
    const char *motd;           /* broadcast message (e.g. "Crake relay"), or NULL */
} ToxNodeConfig;

/* Create and start the node. Returns NULL on failure. */
ToxNode *toxnode_start(const ToxNodeConfig *cfg);

/* Join the network through a known bootstrap node. Call one or more times after
 * start. public_key_hex is 64 hex chars. Returns false on a malformed key. */
bool toxnode_bootstrap(ToxNode *node, const char *host, uint16_t port, const char *public_key_hex);

/* Run one iteration of the node's event loop. Call repeatedly. Returns the
 * number of milliseconds the caller may sleep before the next call. */
uint32_t toxnode_iterate(ToxNode *node);

/* Write this node's DHT public key as 64 hex chars + NUL into out (>= 65). This
 * is the stable key others use to reach it, and what you publish. */
void toxnode_self_public_key_hex(ToxNode *node, char *out);

/* Packets the TCP relay has received from peers connecting in. A relay only
 * receives when something out there reaches it, so a non-zero count is honest
 * proof of reachability. Returns UINT64_MAX if the count cannot be measured
 * (no relay, or profiling not built), which the caller must treat as "unknown",
 * never as reachable. */
uint64_t toxnode_incoming_packets(ToxNode *node);

/* Stop and free the node. */
void toxnode_stop(ToxNode *node);

#ifdef __cplusplus
}
#endif

#endif /* TOXNODE_H */
