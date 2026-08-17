/* JNI bridge: exposes toxnode's C API to the Android app as native methods on
 * com.crake.roost.ToxNode. The node pointer is carried across the boundary as
 * a jlong handle, so the Kotlin side never sees a raw C type.
 *
 * Marshalling is deliberately shallow: toxnode_start() consumes its config only
 * for the duration of the call (ports are bound immediately, the MOTD is copied
 * by bootstrap_set_callbacks, the keys path is used only inside manage_keys), so
 * every borrowed string and array is released as soon as start returns.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "toxnode.h"

JNIEXPORT jlong JNICALL
Java_com_crake_roost_ToxNode_nativeStart(
    JNIEnv *env, jobject thiz,
    jint dht_port, jboolean ipv6, jboolean ipv4_fallback, jboolean lan_discovery,
    jboolean tcp_relay, jintArray tcp_ports, jstring keys_path, jstring motd)
{
    (void)thiz;

    const char *keys = (*env)->GetStringUTFChars(env, keys_path, NULL);
    const char *motd_c = motd != NULL ? (*env)->GetStringUTFChars(env, motd, NULL) : NULL;

    const jsize port_count = tcp_ports != NULL ? (*env)->GetArrayLength(env, tcp_ports) : 0;
    uint16_t *ports = NULL;
    jint *port_elems = NULL;

    if (port_count > 0) {
        port_elems = (*env)->GetIntArrayElements(env, tcp_ports, NULL);
        ports = (uint16_t *)malloc(sizeof(uint16_t) * (size_t)port_count);

        for (jsize i = 0; i < port_count; i++) {
            ports[i] = (uint16_t)port_elems[i];
        }
    }

    ToxNodeConfig cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.dht_port = (uint16_t)dht_port;
    cfg.enable_ipv6 = ipv6 != JNI_FALSE;
    cfg.enable_ipv4_fallback = ipv4_fallback != JNI_FALSE;
    cfg.enable_lan_discovery = lan_discovery != JNI_FALSE;
    cfg.enable_tcp_relay = tcp_relay != JNI_FALSE;
    cfg.tcp_ports = ports;
    cfg.tcp_port_count = (uint16_t)port_count;
    cfg.keys_path = keys;
    cfg.motd = motd_c;

    ToxNode *node = toxnode_start(&cfg);

    if (port_elems != NULL) {
        (*env)->ReleaseIntArrayElements(env, tcp_ports, port_elems, JNI_ABORT);
        free(ports);
    }

    (*env)->ReleaseStringUTFChars(env, keys_path, keys);

    if (motd_c != NULL) {
        (*env)->ReleaseStringUTFChars(env, motd, motd_c);
    }

    return (jlong)(intptr_t)node;
}

JNIEXPORT jboolean JNICALL
Java_com_crake_roost_ToxNode_nativeBootstrap(
    JNIEnv *env, jobject thiz, jlong handle, jstring host, jint port, jstring key_hex)
{
    (void)thiz;

    ToxNode *node = (ToxNode *)(intptr_t)handle;
    const char *host_c = (*env)->GetStringUTFChars(env, host, NULL);
    const char *key_c = (*env)->GetStringUTFChars(env, key_hex, NULL);

    const bool ok = toxnode_bootstrap(node, host_c, (uint16_t)port, key_c);

    (*env)->ReleaseStringUTFChars(env, host, host_c);
    (*env)->ReleaseStringUTFChars(env, key_hex, key_c);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_crake_roost_ToxNode_nativeIterate(JNIEnv *env, jobject thiz, jlong handle)
{
    (void)env;
    (void)thiz;
    return (jint)toxnode_iterate((ToxNode *)(intptr_t)handle);
}

JNIEXPORT jstring JNICALL
Java_com_crake_roost_ToxNode_nativeSelfPublicKey(JNIEnv *env, jobject thiz, jlong handle)
{
    (void)thiz;

    char buf[65];
    buf[0] = '\0';
    toxnode_self_public_key_hex((ToxNode *)(intptr_t)handle, buf);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jlong JNICALL
Java_com_crake_roost_ToxNode_nativeIncoming(JNIEnv *env, jobject thiz, jlong handle)
{
    (void)env;
    (void)thiz;
    /* UINT64_MAX ("unknown") maps to jlong -1, which the Kotlin side reads as
     * "cannot measure" rather than a count. */
    return (jlong)toxnode_incoming_packets((ToxNode *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_crake_roost_ToxNode_nativeStop(JNIEnv *env, jobject thiz, jlong handle)
{
    (void)env;
    (void)thiz;
    toxnode_stop((ToxNode *)(intptr_t)handle);
}
