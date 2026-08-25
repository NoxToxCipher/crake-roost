package com.crake.roost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 24/7 Hardware & Battery Thermal Safeguard.
 * Monitors battery temperature and charging status so continuous background
 * relay operation does not overheat or swell batteries on spare devices.
 */
class ThermalMonitor(private val context: Context) {

    data class BatteryStatus(
        val temperatureCelsius: Float,
        val levelPercent: Int,
        val isCharging: Boolean,
        val isOverheating: Boolean,
    )

    private var currentStatus = BatteryStatus(0f, 0, isCharging = false, isOverheating = false)
    private var receiver: BroadcastReceiver? = null

    fun start(onUpdate: (BatteryStatus) -> Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val tempC = tempRaw / 10.0f
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 0
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

                // Threshold: >= 44°C is hot for 24/7 plugged battery
                val overheating = tempC >= 44.0f

                currentStatus = BatteryStatus(tempC, pct, charging, overheating)
                onUpdate(currentStatus)
            }
        }
        context.registerReceiver(receiver, filter)
    }

    fun stop() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        receiver = null
    }

    fun getStatus(): BatteryStatus = currentStatus
}
