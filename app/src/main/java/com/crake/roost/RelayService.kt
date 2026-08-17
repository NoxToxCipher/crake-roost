package com.crake.roost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the Tox node in the foreground so Android keeps it alive. The node loop
 * lives on its own thread; the service just owns its lifetime and reports status
 * through a persistent notification.
 *
 * Honesty note: "Running" is not "Reachable". A node can be up and still be
 * unreachable behind a NAT, in which case it relays nothing. Until a reachability
 * check exists, the notification says running, never healthy, so the app does not
 * claim something it has not verified.
 */
class RelayService : Service() {

    private val node = ToxNode()
    @Volatile private var running = false
    private var loop: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY

        createChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.status_starting)))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roost:node").apply { acquire() }

        running = true
        loop = thread(name = "roost-node") { runNode() }
        return START_STICKY
    }

    private fun runNode() {
        val keysPath = File(filesDir, "roost.keys").absolutePath
        val ports = intArrayOf(443, 3389, 33445)

        val ok = node.start(
            dhtPort = 33445,
            ipv6 = true,
            ipv4Fallback = true,
            lanDiscovery = false,
            tcpRelay = true,
            tcpPorts = ports,
            keysPath = keysPath,
            motd = getString(R.string.motd),
        )

        if (!ok) {
            State.publicKey = ""
            State.phase = State.Phase.FAILED
            update(getString(R.string.status_failed))
            running = false
            return
        }

        State.publicKey = node.selfPublicKey()
        State.phase = State.Phase.RUNNING
        update(getString(R.string.status_running))

        for (s in SEEDS) {
            node.bootstrap(s.host, s.port, s.key)
        }

        while (running) {
            val sleepMs = node.iterate()
            State.incoming = node.incomingPackets()
            try {
                Thread.sleep(sleepMs.toLong().coerceIn(1, 1000))
            } catch (_: InterruptedException) {
                break
            }
        }

        node.stop()
    }

    override fun onDestroy() {
        running = false
        loop?.interrupt()
        loop?.join(2000)
        wakeLock?.let { if (it.isHeld) it.release() }
        State.phase = State.Phase.STOPPED
        State.incoming = 0
        super.onDestroy()
    }

    private fun update(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_roost)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_desc) }
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "roost.node"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, RelayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RelayService::class.java))
        }
    }
}

/** Shared, honestly-scoped view of node state for the UI. */
object State {
    enum class Phase { STOPPED, RUNNING, FAILED }

    @Volatile var phase: Phase = Phase.STOPPED
    @Volatile var publicKey: String = ""

    /** Relay packets received from peers reaching in. `-1` = not measurable.
     * `0` = nobody has connected yet. `> 0` = provably reachable. */
    @Volatile var incoming: Long = 0
}
