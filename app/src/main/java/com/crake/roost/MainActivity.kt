package com.crake.roost

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView

/**
 * One screen: start or stop the node, and show what it honestly knows about its
 * own state. Colour is never the only signal here; every status carries a word.
 */
class MainActivity : Activity() {

    private lateinit var statusChip: TextView
    private lateinit var keyView: TextView
    private lateinit var toggle: Button
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
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (State.phase == State.Phase.RUNNING) {
                RelayService.stop(this)
            } else {
                RelayService.start(this)
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
                // A peer connecting in is the only thing that earns the green.
                State.incoming > 0 -> setChip(R.string.status_reachable, R.drawable.chip_ok, R.color.ok)
                // Cannot measure: stay honest, do not claim reachable.
                State.incoming == ToxNode.UNKNOWN -> setChip(R.string.status_running, R.drawable.chip_warn, R.color.warn)
                // Running, but nobody has reached it yet.
                else -> setChip(R.string.status_running_idle, R.drawable.chip_warn, R.color.warn)
            }
        }

        toggle.setText(if (State.phase == State.Phase.RUNNING) R.string.stop else R.string.start)

        keyView.text = if (State.publicKey.isEmpty()) {
            getString(R.string.key_none)
        } else {
            State.publicKey
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
