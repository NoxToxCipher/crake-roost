package com.crake.roost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the node after a reboot, but only if the user asked it to. A relay
 * that silently relaunches itself would be a surprise; opting in is a stored
 * preference, checked here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("roost", Context.MODE_PRIVATE)
        if (prefs.getBoolean("start_on_boot", false)) {
            RelayService.start(context)
        }
    }
}
