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
 * Runs the Tox relay node as an Android foreground service.
 * Manages the native node iteration loop, automatic UPnP port mappings,
 * and 24/7 hardware battery thermal safeguards.
 */
class RelayService : Service() {

    private val node = ToxNode()
    @Volatile private var running = false
    private var loop: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var thermalMonitor: ThermalMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY

        createChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.status_starting)))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "roost:node").apply { acquire() }

        running = true
        State.startTimeMs = System.currentTimeMillis()

        // Start hardware thermal monitoring
        thermalMonitor = ThermalMonitor(this).apply {
            start { status ->
                State.batteryTempC = status.temperatureCelsius
                State.batteryLevel = status.levelPercent
                State.isOverheating = status.isOverheating
            }
        }

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

        // Trigger automatic UPnP port forwarding asynchronously
        thread(name = "roost-upnp") {
            State.upnpStatus = getString(R.string.upnp_discovering)
            val result = Upnp.tryForwardPorts(listOf(33445 to "UDP", 33445 to "TCP", 3389 to "TCP"))
            State.upnpStatus = if (result.success) {
                if (result.externalIp.isNotEmpty()) {
                    getString(R.string.upnp_success_ip, result.externalIp)
                } else {
                    getString(R.string.upnp_success)
                }
            } else {
                getString(R.string.upnp_failed, result.details)
            }
        }

        for (s in SEEDS) {
            node.bootstrap(s.host, s.port, s.key)
        }

        while (running) {
            val sleepMs = node.iterate()
            State.incoming = node.incomingPackets()
            State.outgoing = node.outgoingPackets()

            // Adaptive thermal throttle if battery is overheating
            val actualDelay = if (State.isOverheating) {
                (sleepMs * 4).toLong().coerceIn(100, 2000)
            } else {
                sleepMs.toLong().coerceIn(1, 1000)
            }

            try {
                Thread.sleep(actualDelay)
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
        thermalMonitor?.stop()
        thermalMonitor = null
        wakeLock?.let { if (it.isHeld) it.release() }
        State.phase = State.Phase.STOPPED
        State.incoming = 0
        State.outgoing = 0
        State.startTimeMs = 0L
        State.upnpStatus = ""
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

/** Shared, live view of node state and telemetry for the UI. */
object State {
    enum class Phase { STOPPED, RUNNING, FAILED }

    @Volatile var phase: Phase = Phase.STOPPED
    @Volatile var publicKey: String = ""

    /** Packets received from external peers. `> 0` = provably reachable. */
    @Volatile var incoming: Long = 0

    /** Packets transmitted to peers. */
    @Volatile var outgoing: Long = 0

    /** Startup timestamp for live uptime tracking. */
    @Volatile var startTimeMs: Long = 0L

    /** Router UPnP mapping status string. */
    @Volatile var upnpStatus: String = ""

    /** Live hardware battery temperature in degrees Celsius. */
    @Volatile var batteryTempC: Float = 0f

    /** Live battery level percentage. */
    @Volatile var batteryLevel: Int = 0

    /** Thermal throttle trigger flag. */
    @Volatile var isOverheating: Boolean = false
}
