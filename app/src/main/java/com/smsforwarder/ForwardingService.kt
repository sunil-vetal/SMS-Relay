package com.smsforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class ForwardingService : Service() {

    companion object {
        private const val TAG             = "Relay/Service"
        private const val NOTIF_CHANNEL   = "relay_channel"
        private const val NOTIF_ID        = 1001
        private const val RESULT_NOTIF_ID = 1002
    }

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var db: SmsDatabase

    override fun onCreate() {
        super.onCreate()
        db = SmsDatabase(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildForegroundNotification())

        val sender = intent?.getStringExtra(SmsReceiver.EXTRA_SENDER)  ?: "Unknown"
        val body   = intent?.getStringExtra(SmsReceiver.EXTRA_BODY)    ?: ""
        val timeMs = intent?.getLongExtra(SmsReceiver.EXTRA_TIME_MS, System.currentTimeMillis())
                     ?: System.currentTimeMillis()

        scope.launch {
            forward(sender, body, timeMs)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private fun forward(sender: String, body: String, timeMs: Long) {
        val method  = AppPrefs.forwardMethod(this).trim().lowercase()
        val include = AppPrefs.includeSender(this)

        val displayTime = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timeMs))
        val fullMessage = if (include) "From: $sender\nAt: $displayTime\n\n$body" else body

        Log.i(TAG, "Forwarding via $method — sender=$sender")

        // Internet-dependent methods: queue if offline
        val needsInternet = method in listOf("email", "telegram", "ntfy", "discord", "gchat")
        if (needsInternet && !isNetworkAvailable()) {
            Log.w(TAG, "No network — queuing message for later")
            db.enqueue(sender, body, timeMs, method)
            db.addLog(sender, body, method, "queued")
            QueueWorker.schedule(this)
            notifyQueued(sender)
            return
        }

        val (success, errorMsg) = when (method) {
            "email"    -> forwardEmail(sender, fullMessage, displayTime)
            "whatsapp" -> forwardWhatsApp(fullMessage)
            "gvoice"   -> forwardGoogleVoice(fullMessage)
            "telegram" -> forwardTelegram(fullMessage)
            "ntfy"     -> forwardNtfy(sender, body)
            "discord"  -> forwardDiscord(sender, fullMessage)
            "gchat"    -> forwardGChat(fullMessage)
            else       -> Pair(false, "Unknown method: $method")
        }

        db.addLog(sender, body, method, if (success) "sent" else "failed", errorMsg)

        if (!success && needsInternet && errorMsg != null) {
            db.enqueue(sender, body, timeMs, method)
            QueueWorker.schedule(this)
        }

        notifyResult(sender, success, if (!success && needsInternet) "Queued for retry. $errorMsg" else errorMsg)
    }

    // ── Network check ─────────────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps    = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // ── Email (SMTP) ──────────────────────────────────────────────────────────

    private fun forwardEmail(sender: String, fullMessage: String, time: String): Pair<Boolean, String?> {
        val host      = AppPrefs.smtpHost(this)
        val port      = AppPrefs.smtpPort(this)
        val user      = AppPrefs.smtpUser(this)
        val pass      = AppPrefs.smtpPass(this)
        val recipient = AppPrefs.emailRecipient(this)
        val useSsl    = AppPrefs.smtpSsl(this)

        if (user.isBlank() || pass.isBlank() || recipient.isBlank())
            return Pair(false, "Email settings incomplete.")

        return try {
            val props = Properties().apply {
                if (useSsl) {
                    put("mail.smtp.host", host)
                    put("mail.smtp.port", port.toString())
                    put("mail.smtp.socketFactory.port", port.toString())
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.auth", "true")
                } else {
                    put("mail.smtp.host", host)
                    put("mail.smtp.port", port.toString())
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.auth", "true")
                }
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, pass)
            })
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(user, "Relay"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
                subject = "SMS from $sender at $time"
                setText(fullMessage, "utf-8")
                sentDate = Date()
            }
            Transport.send(msg)
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, e.message)
        }
    }

    // ── WhatsApp (Intent) ─────────────────────────────────────────────────────

    private fun forwardWhatsApp(fullMessage: String): Pair<Boolean, String?> {
        val number = AppPrefs.waNumber(this).replace(Regex("[^+0-9]"), "")
        if (number.isBlank()) return Pair(false, "WhatsApp number not configured.")
        return try {
            val uri = android.net.Uri.parse("https://wa.me/$number?text=${android.net.Uri.encode(fullMessage)}")
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Pair(true, null)
        } catch (e: android.content.ActivityNotFoundException) {
            Pair(false, "WhatsApp not installed.")
        } catch (e: Exception) {
            Pair(false, e.message)
        }
    }

    // ── Google Voice (SMS relay) ──────────────────────────────────────────────

    private fun forwardGoogleVoice(fullMessage: String): Pair<Boolean, String?> {
        val gvNumber = AppPrefs.gvNumber(this)
        if (gvNumber.isBlank()) return Pair(false, "Google Voice number not configured.")
        return try {
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val parts = smsManager.divideMessage(fullMessage)
            smsManager.sendMultipartTextMessage(gvNumber, null, parts, null, null)
            Pair(true, null)
        } catch (e: SecurityException) {
            Pair(false, "SEND_SMS permission not granted.")
        } catch (e: Exception) {
            Pair(false, e.message)
        }
    }

    // ── Telegram Bot ──────────────────────────────────────────────────────────
    // POST https://api.telegram.org/bot{token}/sendMessage
    // Body: { chat_id: "...", text: "...", parse_mode: "HTML" }

    private fun forwardTelegram(fullMessage: String): Pair<Boolean, String?> {
        val token  = AppPrefs.telegramToken(this)
        val chatId = AppPrefs.telegramChatId(this)
        if (token.isBlank() || chatId.isBlank())
            return Pair(false, "Telegram token or chat ID not configured.")

        return try {
            val url  = URL("https://api.telegram.org/bot$token/sendMessage")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput        = true
                connectTimeout  = 10000
                readTimeout     = 10000
            }
            val escaped = fullMessage.escapeJson()
            val json = """{"chat_id":"$chatId","text":"$escaped"}"""
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Pair(true, null) else Pair(false, "HTTP $code from Telegram")
        } catch (e: Exception) {
            Pair(false, e.message)
        }
    }

    // ── ntfy.sh ───────────────────────────────────────────────────────────────
    // PUT https://ntfy.sh/{topic}
    // Headers: Title, Body is plain text

    private fun forwardNtfy(sender: String, body: String): Pair<Boolean, String?> {
        val topic  = AppPrefs.ntfyTopic(this).trim()
        val server = AppPrefs.ntfyServer(this).trimEnd('/')
        if (topic.isBlank()) return Pair(false, "ntfy topic not configured.")

        return try {
            val url  = URL("$server/$topic")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Title", "SMS from $sender")
                setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                setRequestProperty("Priority", "default")
                doOutput       = true
                connectTimeout = 10000
                readTimeout    = 10000
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Pair(true, null) else Pair(false, "HTTP $code from ntfy")
        } catch (e: Exception) {
            Pair(false, e.message)
        }
    }

    // ── Discord Webhook ───────────────────────────────────────────────────────
    // POST https://discord.com/api/webhooks/{id}/{token}
    // Body: { "content": "..." }  (max 2000 chars)

    private fun forwardDiscord(sender: String, fullMessage: String): Pair<Boolean, String?> {
        val webhookUrl = AppPrefs.discordWebhook(this)
        if (webhookUrl.isBlank()) return Pair(false, "Discord webhook URL not configured.")

        return try {
            val url  = URL(webhookUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput       = true
                connectTimeout = 10000
                readTimeout    = 10000
            }
            // Discord max content = 2000 chars; truncate if needed
            val content = fullMessage.take(1990).escapeJson()
            val json = """{"content":"$content"}"""
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json) }
            val code = conn.responseCode
            conn.disconnect()
            // Discord returns 204 No Content on success
            if (code in 200..299) Pair(true, null) else Pair(false, "HTTP $code from Discord")
        } catch (e: Exception) {
            Pair(false, e.message)
        }
    }

    // ── Google Chat Webhook ───────────────────────────────────────────────────

    private fun forwardGChat(fullMessage: String): Pair<Boolean, String?> {
        val webhookUrl = AppPrefs.gchatWebhook(this)
        if (webhookUrl.isBlank()) return Pair(false, "Google Chat webhook URL not configured.")

        return try {
            val url  = URL(webhookUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput       = true
                connectTimeout = 10000
                readTimeout    = 10000
            }
            val escaped = fullMessage.escapeJson()
            val json = """{"text":"$escaped"}"""
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) Pair(true, null) else Pair(false, "HTTP $code from Google Chat")
        } catch (e: Exception) {
            Pair(false, e.message)
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Relay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Background SMS forwarding service"
                    setShowBadge(false)
                }
            )
            mgr.createNotificationChannel(
                NotificationChannel("sms_result_channel", "Forward Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "SMS forwarding results"
                }
            )
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Relay")
            .setContentText("Forwarding incoming SMS…")
            .setSmallIcon(R.drawable.ic_relay)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyResult(sender: String, success: Boolean, errorMsg: String?) {
        val mgr = getSystemService(NotificationManager::class.java)
        val pi  = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        mgr.notify(
            RESULT_NOTIF_ID + System.currentTimeMillis().toInt() % 1000,
            NotificationCompat.Builder(this, "sms_result_channel")
                .setSmallIcon(R.drawable.ic_relay)
                .setContentTitle(if (success) "SMS forwarded ✓" else "Forwarding failed ✗")
                .setContentText(if (success) "From: $sender" else errorMsg ?: "Unknown error")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notifyQueued(sender: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        val pi  = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        mgr.notify(
            RESULT_NOTIF_ID + System.currentTimeMillis().toInt() % 1000,
            NotificationCompat.Builder(this, "sms_result_channel")
                .setSmallIcon(R.drawable.ic_relay)
                .setContentTitle("SMS queued 📥")
                .setContentText("From $sender — will forward when online")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}

// ── Extension ─────────────────────────────────────────────────────────────────

private fun String.escapeJson(): String = this
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")
