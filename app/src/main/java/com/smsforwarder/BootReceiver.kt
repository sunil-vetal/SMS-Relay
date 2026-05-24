package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-activates the SMS forwarder after a device reboot.
 * The BroadcastReceiver registration in AndroidManifest.xml handles this
 * automatically — this class just logs the boot event.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("Relay/Boot", "Device booted — Relay is active: ${AppPrefs.isEnabled(context)}")
            // No additional setup needed; SmsReceiver is statically registered
            // in the manifest and automatically active after boot.
        }
    }
}
