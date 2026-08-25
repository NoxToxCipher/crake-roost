package com.crake.roost

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast

/**
 * Main dashboard: control relay node state, monitor verified reachability,
 * inspect live network telemetry, and monitor hardware thermal safeguards.
 */
class MainActivity : Activity() {

    private lateinit var statusChip: TextView
    private lateinit var keyView: TextView
    private lateinit var copyKeyBtn: Button
    private lateinit var toggle: Button
    private lateinit var telemetryCard: View
    private lateinit var uptimeView: TextView
    private lateinit var trafficView: TextView
    private lateinit var upnpView: TextView
    private lateinit var thermalView: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusChip = findViewById(R.id.statusChip)
        keyView = findViewById(R.id.keyView)
        copyKeyBtn = findViewById(R.id.copyKeyBtn)
        toggle = findViewById(R.id.toggle)
        telemetryCard = findViewById(R.id.telemetryCard)
        uptimeView = findViewById(R.id.uptimeView)
        trafficView = findViewById(R.id.trafficView)
        upnpView = findViewById(R.id.upnpView)
        thermalView = findViewById(R.id.thermalView)

        toggle.setOnClickListener {
            if (State.phase == State.Phase.RUNNING) {
                RelayService.stop(this)
            } else {
                RelayService.start(this)
            }
        }

        copyKeyBtn.setOnClickListener {
            copyPublicKeyToClipboard()
        }

        keyView.setOnClickListener {
            if (State.publicKey.isNotEmpty()) {
                copyPublicKeyToClipboard()
            }
        }

        val bootBox = findViewById<CheckBox>(R.id.startOnBoot)
        val prefs = getSharedPreferences("roost", Context.MODE_PRIVATE)
        bootBox.isChecked = prefs.getBoolean("start_on_boot", false)
        bootBox.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("start_on_boot", checked).apply()
        }

        findViewById<Button>(R.id.batteryBtn).setOnClickListener { requestIgnoreBatteryOptimizations() }

        requestNotificationPermission()
    }

    private fun copyPublicKeyToClipboard() {
        val key = State.publicKey
        if (key.isEmpty()) return

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Tox Public Key", key)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, getString(R.string.key_copied), Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(poll)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(poll)
    }

    private fun render() {
        when (State.phase) {
            State.Phase.STOPPED -> setChip(R.string.status_stopped, R.drawable.chip_neutral, R.color.ink_dim)
            State.Phase.FAILED -> setChip(R.string.status_failed, R.drawable.chip_bad, R.color.danger)
            State.Phase.RUNNING -> when {
                State.incoming > 0 -> setChip(R.string.status_reachable, R.drawable.chip_ok, R.color.ok)
                State.incoming == ToxNode.UNKNOWN -> setChip(R.string.status_running, R.drawable.chip_warn, R.color.warn)
                else -> setChip(R.string.status_running_idle, R.drawable.chip_warn, R.color.warn)
            }
        }

        toggle.setText(if (State.phase == State.Phase.RUNNING) R.string.stop else R.string.start)

        if (State.publicKey.isEmpty()) {
            keyView.text = getString(R.string.key_none)
            keyView.setTextColor(getColor(R.color.ink_dim))
            copyKeyBtn.visibility = View.GONE
            telemetryCard.visibility = View.GONE
        } else {
            keyView.text = State.publicKey
            keyView.setTextColor(getColor(R.color.ink))
            copyKeyBtn.visibility = View.VISIBLE
            telemetryCard.visibility = View.VISIBLE
        }

        // Render live telemetry
        if (State.phase == State.Phase.RUNNING && State.startTimeMs > 0L) {
            val elapsedSec = (System.currentTimeMillis() - State.startTimeMs) / 1000
            val hours = elapsedSec / 3600
            val minutes = (elapsedSec % 3600) / 60
            val seconds = elapsedSec % 60
            uptimeView.text = String.format("%02dh %02dm %02ds", hours, minutes, seconds)

            val inPkts = if (State.incoming == ToxNode.UNKNOWN) 0 else State.incoming
            val outPkts = if (State.outgoing == ToxNode.UNKNOWN) 0 else State.outgoing
            trafficView.text = getString(R.string.traffic_value, inPkts, outPkts)

            upnpView.text = if (State.upnpStatus.isNotEmpty()) State.upnpStatus else "--"
        }

        // Render battery & thermal status
        if (State.batteryTempC > 0f) {
            val chargeState = if (State.batteryLevel >= 100) {
                getString(R.string.thermal_charging)
            } else {
                getString(R.string.thermal_battery)
            }
            if (State.isOverheating) {
                thermalView.text = getString(R.string.thermal_hot, State.batteryTempC)
                thermalView.setTextColor(getColor(R.color.danger))
            } else {
                thermalView.text = getString(R.string.thermal_normal, State.batteryTempC, State.batteryLevel, chargeState)
                thermalView.setTextColor(getColor(R.color.ink))
            }
        } else {
            thermalView.text = "--"
        }
    }

    private fun setChip(textRes: Int, bgRes: Int, colorRes: Int) {
        statusChip.setText(textRes)
        statusChip.setBackgroundResource(bgRes)
        statusChip.setTextColor(getColor(colorRes))
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }
}
