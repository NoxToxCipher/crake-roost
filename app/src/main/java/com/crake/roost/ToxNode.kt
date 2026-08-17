package com.crake.roost

/**
 * Kotlin handle to the native Tox node (bootstrap + UDP DHT + TCP relay).
 *
 * Each `external` method binds by name to a Java_com_crake_roost_ToxNode_*
 * function in toxnode_jni.c, so this class's package and name must not change
 * without changing those symbols too. The native side owns a pointer; this side
 * only holds it as an opaque [handle].
 *
 * Not thread-safe: create it on the node thread and call [iterate] in a loop
 * from that same thread.
 */
class ToxNode {

    private var handle: Long = 0L

    val isRunning: Boolean get() = handle != 0L

    /** Start the node. Returns false if the native side failed to come up. */
    fun start(
        dhtPort: Int,
        ipv6: Boolean,
        ipv4Fallback: Boolean,
        lanDiscovery: Boolean,
        tcpRelay: Boolean,
        tcpPorts: IntArray,
        keysPath: String,
        motd: String?,
    ): Boolean {
        check(handle == 0L) { "already started" }
        handle = nativeStart(dhtPort, ipv6, ipv4Fallback, lanDiscovery, tcpRelay, tcpPorts, keysPath, motd)
        return handle != 0L
    }

    /** Join the network through a known bootstrap node. */
    fun bootstrap(host: String, port: Int, publicKeyHex: String): Boolean =
        if (handle != 0L) nativeBootstrap(handle, host, port, publicKeyHex) else false

    /** One turn of the event loop. Returns the milliseconds the caller may sleep. */
    fun iterate(): Int = if (handle != 0L) nativeIterate(handle) else 1000

    /** This node's stable DHT public key as 64 hex chars, or "" if not running. */
    fun selfPublicKey(): String = if (handle != 0L) nativeSelfPublicKey(handle) else ""

    /**
     * Packets the relay has received from peers reaching in. A positive number
     * is honest proof of reachability. [Reach.UNKNOWN] (-1) means it cannot be
     * measured and must never be read as reachable.
     */
    fun incomingPackets(): Long = if (handle != 0L) nativeIncoming(handle) else 0L

    fun stop() {
        if (handle != 0L) {
            nativeStop(handle)
            handle = 0L
        }
    }

    private external fun nativeStart(
        dhtPort: Int, ipv6: Boolean, ipv4Fallback: Boolean, lanDiscovery: Boolean,
        tcpRelay: Boolean, tcpPorts: IntArray, keysPath: String, motd: String?,
    ): Long

    private external fun nativeBootstrap(handle: Long, host: String, port: Int, keyHex: String): Boolean
    private external fun nativeIterate(handle: Long): Int
    private external fun nativeSelfPublicKey(handle: Long): String
    private external fun nativeIncoming(handle: Long): Long
    private external fun nativeStop(handle: Long)

    companion object {
        /** Sentinel returned by [incomingPackets] when reachability cannot be measured. */
        const val UNKNOWN: Long = -1L

        init {
            System.loadLibrary("toxnode")
        }
    }
}
