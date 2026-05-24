package com.smsforwarder

import android.content.Context
import android.util.Log
import androidx.work.*
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

class QueueWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val TAG       = "Relay/Queue"
        private const val WORK_NAME = "queue_flush"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<QueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            Log.i(TAG, "Queue flush scheduled")
        }
    }

    override suspend fun doWork(): Result {
        val db      = SmsDatabase(ctx)
        val pending = db.allPending()
        if (pending.isEmpty()) return Result.success()

        Log.i(TAG, "Flushing ${pending.size} queued message(s)")
        var allOk = true

        for (msg in pending) {
            val displayTime = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(msg.timeMs))
            val fullMessage = if (AppPrefs.includeSender(ctx))
                "From: ${msg.sender}\nAt: $displayTime\n\n${msg.body}" else msg.body

            val (success, error) = when (msg.method) {
                "email"   -> sendEmail(msg.sender, fullMessage, displayTime)
                "telegram"-> sendTelegram(fullMessage)
                "ntfy"    -> sendNtfy(msg.sender, msg.body)
                "discord" -> sendDiscord(msg.sender, fullMessage)
                "gchat"   -> sendGChat(fullMessage)
                else      -> Pair(false, "Method ${msg.method} not retryable in worker")
            }

            if (success) {
                db.deletePending(msg.id)
                db.addLog(msg.sender, msg.body, msg.method, "sent")
            } else {
                db.incrementRetry(msg.id)
                db.addLog(msg.sender, msg.body, msg.method, "failed", error)
                allOk = false
            }
        }
        return if (allOk) Result.success() else Result.retry()
    }

    private fun sendEmail(sender: String, fullMessage: String, time: String): Pair<Boolean, String?> {
        val host = AppPrefs.smtpHost(ctx); val port = AppPrefs.smtpPort(ctx)
        val user = AppPrefs.smtpUser(ctx); val pass = AppPrefs.smtpPass(ctx)
        val recipient = AppPrefs.emailRecipient(ctx); val useSsl = AppPrefs.smtpSsl(ctx)
        if (user.isBlank() || pass.isBlank() || recipient.isBlank()) return Pair(false, "Email settings incomplete")
        return try {
            val props = Properties().apply {
                if (useSsl) { put("mail.smtp.host", host); put("mail.smtp.port", port.toString()); put("mail.smtp.socketFactory.port", port.toString()); put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory"); put("mail.smtp.auth", "true") }
                else { put("mail.smtp.host", host); put("mail.smtp.port", port.toString()); put("mail.smtp.starttls.enable", "true"); put("mail.smtp.auth", "true") }
                put("mail.smtp.connectiontimeout", "15000"); put("mail.smtp.timeout", "15000")
            }
            val session = Session.getInstance(props, object : Authenticator() { override fun getPasswordAuthentication() = PasswordAuthentication(user, pass) })
            val msg = MimeMessage(session).apply { setFrom(InternetAddress(user, "Relay")); setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient)); subject = "SMS from $sender at $time"; setText(fullMessage, "utf-8"); sentDate = Date() }
            Transport.send(msg)
            Pair(true, null)
        } catch (e: Exception) { Pair(false, e.message) }
    }

    private fun sendTelegram(fullMessage: String): Pair<Boolean, String?> {
        val token = AppPrefs.telegramToken(ctx); val chatId = AppPrefs.telegramChatId(ctx)
        if (token.isBlank() || chatId.isBlank()) return Pair(false, "Telegram not configured")
        return httpPost("https://api.telegram.org/bot$token/sendMessage",
            """{"chat_id":"$chatId","text":"${fullMessage.escapeJson()}"}""")
    }

    private fun sendNtfy(sender: String, body: String): Pair<Boolean, String?> {
        val topic = AppPrefs.ntfyTopic(ctx); val server = AppPrefs.ntfyServer(ctx).trimEnd('/')
        if (topic.isBlank()) return Pair(false, "ntfy topic not configured")
        return try {
            val conn = (URL("$server/$topic").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Title", "SMS from $sender")
                setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
                doOutput = true; connectTimeout = 15000; readTimeout = 15000
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            val code = conn.responseCode; conn.disconnect()
            if (code in 200..299) Pair(true, null) else Pair(false, "HTTP $code")
        } catch (e: Exception) { Pair(false, e.message) }
    }

    private fun sendDiscord(sender: String, fullMessage: String): Pair<Boolean, String?> {
        val webhook = AppPrefs.discordWebhook(ctx)
        if (webhook.isBlank()) return Pair(false, "Discord webhook not configured")
        return httpPost(webhook, """{"content":"${fullMessage.take(1990).escapeJson()}"}""")
    }

    private fun sendGChat(fullMessage: String): Pair<Boolean, String?> {
        val webhook = AppPrefs.gchatWebhook(ctx)
        if (webhook.isBlank()) return Pair(false, "Google Chat webhook not configured")
        return httpPost(webhook, """{"text":"${fullMessage.escapeJson()}"}""")
    }

    private fun httpPost(urlStr: String, json: String): Pair<Boolean, String?> {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true; connectTimeout = 15000; readTimeout = 15000
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(json) }
            val code = conn.responseCode; conn.disconnect()
            if (code in 200..299) Pair(true, null) else Pair(false, "HTTP $code")
        } catch (e: Exception) { Pair(false, e.message) }
    }
}

private fun String.escapeJson(): String = this
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
