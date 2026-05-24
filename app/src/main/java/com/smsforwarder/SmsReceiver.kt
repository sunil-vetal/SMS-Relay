package com.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

/**
 * Receives every incoming SMS via the SMS_RECEIVED broadcast.
 *
 * Android delivers this even in the background as long as:
 *  - RECEIVE_SMS permission is granted
 *  - Battery optimisation exemption is granted (user is prompted from MainActivity)
 *
 * We keep the work here minimal — just parse sender + body, then hand off to
 * ForwardingService which runs on a background thread.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Relay/Receiver"

        // Intent extras used to pass data to ForwardingService
        const val EXTRA_SENDER  = "extra_sender"
        const val EXTRA_BODY    = "extra_body"
        const val EXTRA_TIME_MS = "extra_time_ms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // App must be enabled in settings
        if (!AppPrefs.isEnabled(context)) {
            Log.d(TAG, "Forwarder disabled — ignoring SMS")
            return
        }

        // Parse all PDU fragments into SmsMessage objects
        val messages: Array<SmsMessage> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } else {
            @Suppress("DEPRECATION")
            val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
            pdus.mapNotNull { pdu ->
                SmsMessage.createFromPdu(pdu as ByteArray)
            }.toTypedArray()
        }

        if (messages.isEmpty()) return

        // Concatenate multi-part messages (long SMS sent in fragments)
        val sender = messages[0].displayOriginatingAddress ?: "Unknown"
        val body   = messages.joinToString("") { it.messageBody ?: "" }
        val timeMs = messages[0].timestampMillis

        Log.i(TAG, "SMS received from $sender (${body.length} chars)")

        // Optional sender filter
        val filter = AppPrefs.filterSender(context)
        if (filter.isNotBlank() && !sender.contains(filter, ignoreCase = true)) {
            Log.d(TAG, "Sender $sender does not match filter '$filter' — skipping")
            return
        }

        // Delegate to ForwardingService for background network/SMS work
        val serviceIntent = Intent(context, ForwardingService::class.java).apply {
            putExtra(EXTRA_SENDER,  sender)
            putExtra(EXTRA_BODY,    body)
            putExtra(EXTRA_TIME_MS, timeMs)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
