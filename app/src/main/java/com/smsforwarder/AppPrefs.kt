package com.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

object AppPrefs {

    // General
    const val KEY_ENABLED          = "pref_enabled"
    const val KEY_FORWARD_METHOD   = "pref_forward_method"   // "email"|"whatsapp"|"gvoice"|"telegram"|"ntfy"|"discord"
    const val KEY_FILTER_SENDER    = "pref_filter_sender"
    const val KEY_INCLUDE_SENDER   = "pref_include_sender"

    // Email (SMTP)
    const val KEY_EMAIL_RECIPIENT  = "pref_email_recipient"
    const val KEY_SMTP_HOST        = "pref_smtp_host"
    const val KEY_SMTP_PORT        = "pref_smtp_port"
    const val KEY_SMTP_USER        = "pref_smtp_user"
    const val KEY_SMTP_PASS        = "pref_smtp_pass"
    const val KEY_SMTP_SSL         = "pref_smtp_ssl"

    // WhatsApp
    const val KEY_WA_NUMBER        = "pref_wa_number"

    // Google Voice
    const val KEY_GV_NUMBER        = "pref_gv_number"

    // Telegram Bot
    const val KEY_TELEGRAM_TOKEN   = "pref_telegram_token"
    const val KEY_TELEGRAM_CHAT_ID = "pref_telegram_chat_id"

    // ntfy.sh
    const val KEY_NTFY_TOPIC       = "pref_ntfy_topic"
    const val KEY_NTFY_SERVER      = "pref_ntfy_server"      // default: https://ntfy.sh

    // Discord webhook
    const val KEY_DISCORD_WEBHOOK  = "pref_discord_webhook"

    // Google Chat webhook
    const val KEY_GCHAT_WEBHOOK    = "pref_gchat_webhook"

    // Defaults
    const val DEFAULT_SMTP_HOST    = "smtp.gmail.com"
    const val DEFAULT_SMTP_PORT    = "587"
    const val DEFAULT_NTFY_SERVER  = "https://ntfy.sh"

    fun prefs(ctx: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(ctx)

    fun isEnabled(ctx: Context)         = prefs(ctx).getBoolean(KEY_ENABLED, false)
    fun forwardMethod(ctx: Context)     = prefs(ctx).getString(KEY_FORWARD_METHOD, "email") ?: "email"
    fun filterSender(ctx: Context)      = prefs(ctx).getString(KEY_FILTER_SENDER, "") ?: ""
    fun includeSender(ctx: Context)     = prefs(ctx).getBoolean(KEY_INCLUDE_SENDER, true)

    fun emailRecipient(ctx: Context)    = prefs(ctx).getString(KEY_EMAIL_RECIPIENT, "") ?: ""
    fun smtpHost(ctx: Context)          = prefs(ctx).getString(KEY_SMTP_HOST, DEFAULT_SMTP_HOST) ?: DEFAULT_SMTP_HOST
    fun smtpPort(ctx: Context)          = prefs(ctx).getString(KEY_SMTP_PORT, DEFAULT_SMTP_PORT)?.toIntOrNull() ?: 587
    fun smtpUser(ctx: Context)          = prefs(ctx).getString(KEY_SMTP_USER, "") ?: ""
    fun smtpPass(ctx: Context)          = prefs(ctx).getString(KEY_SMTP_PASS, "") ?: ""
    fun smtpSsl(ctx: Context)           = prefs(ctx).getBoolean(KEY_SMTP_SSL, false)

    fun waNumber(ctx: Context)          = prefs(ctx).getString(KEY_WA_NUMBER, "") ?: ""
    fun gvNumber(ctx: Context)          = prefs(ctx).getString(KEY_GV_NUMBER, "") ?: ""

    fun telegramToken(ctx: Context)     = prefs(ctx).getString(KEY_TELEGRAM_TOKEN, "") ?: ""
    fun telegramChatId(ctx: Context)    = prefs(ctx).getString(KEY_TELEGRAM_CHAT_ID, "") ?: ""

    fun ntfyTopic(ctx: Context)         = prefs(ctx).getString(KEY_NTFY_TOPIC, "") ?: ""
    fun ntfyServer(ctx: Context)        = prefs(ctx).getString(KEY_NTFY_SERVER, DEFAULT_NTFY_SERVER) ?: DEFAULT_NTFY_SERVER

    fun discordWebhook(ctx: Context)    = prefs(ctx).getString(KEY_DISCORD_WEBHOOK, "") ?: ""

    fun gchatWebhook(ctx: Context)      = prefs(ctx).getString(KEY_GCHAT_WEBHOOK, "") ?: ""
}
