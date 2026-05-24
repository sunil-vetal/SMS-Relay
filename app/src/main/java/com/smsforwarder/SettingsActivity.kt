package com.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smsforwarder.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // Currently selected method — driven by card taps
    private var selectedMethod = "email"

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isEmpty()) showToast("All permissions granted ✓") else showToast("Denied: ${denied.joinToString()}")
        updatePermissionBadge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        selectedMethod = AppPrefs.forwardMethod(this)

        loadSavedValues()
        setupMethodCards()
        setupButtons()
        updatePermissionBadge()
        updateVersionInfo()
        updateMethodCardSelection()
        updatePanelVisibility()
    }

    // ── Method card selector ──────────────────────────────────────────────────

    private fun setupMethodCards() {
        binding.cardEmail.setOnClickListener    { selectMethod("email") }
        binding.cardWhatsApp.setOnClickListener { selectMethod("whatsapp") }
        binding.cardGVoice.setOnClickListener   { selectMethod("gvoice") }
        binding.cardTelegram.setOnClickListener { selectMethod("telegram") }
        binding.cardNtfy.setOnClickListener     { selectMethod("ntfy") }
        binding.cardDiscord.setOnClickListener  { selectMethod("discord") }
        binding.cardGChat.setOnClickListener    { selectMethod("gchat") }
    }

    private fun selectMethod(method: String) {
        selectedMethod = method
        saveSettings(shouldFinish = false) // Auto-save all fields and the new active method
        updateMethodCardSelection()
        updatePanelVisibility()
    }

    private fun updateMethodCardSelection() {
        val selectedStroke = getColor(R.color.primary)
        val defaultStroke  = getColor(R.color.divider)
        val selectedBg     = getColor(R.color.card_selected_bg)
        val defaultBg      = getColor(R.color.surface)

        mapOf(
            "email"    to binding.cardEmail,
            "whatsapp" to binding.cardWhatsApp,
            "gvoice"   to binding.cardGVoice,
            "telegram" to binding.cardTelegram,
            "ntfy"     to binding.cardNtfy,
            "discord"  to binding.cardDiscord,
            "gchat"    to binding.cardGChat
        ).forEach { (method, card) ->
            val isSelected = method == selectedMethod
            card.strokeColor     = if (isSelected) selectedStroke else defaultStroke
            card.strokeWidth     = if (isSelected) 2 else 1
            card.setCardBackgroundColor(if (isSelected) selectedBg else defaultBg)
        }

        // Update config panel title to match selection
        binding.tvPanelTitle.text = when (selectedMethod) {
            "email"    -> "Email (SMTP) Settings"
            "whatsapp" -> "WhatsApp Settings"
            "gvoice"   -> "Google Voice Settings"
            "telegram" -> "Telegram Bot Settings"
            "ntfy"     -> "ntfy Settings"
            "discord"  -> "Discord Webhook Settings"
            "gchat"    -> "Google Chat Settings"
            else       -> "Settings"
        }
    }

    private fun updatePanelVisibility() {
        binding.panelEmail.visibility    = if (selectedMethod == "email")    View.VISIBLE else View.GONE
        binding.panelWhatsApp.visibility = if (selectedMethod == "whatsapp") View.VISIBLE else View.GONE
        binding.panelGVoice.visibility   = if (selectedMethod == "gvoice")   View.VISIBLE else View.GONE
        binding.panelTelegram.visibility = if (selectedMethod == "telegram") View.VISIBLE else View.GONE
        binding.panelNtfy.visibility     = if (selectedMethod == "ntfy")     View.VISIBLE else View.GONE
        binding.panelDiscord.visibility  = if (selectedMethod == "discord")  View.VISIBLE else View.GONE
        binding.panelGChat.visibility    = if (selectedMethod == "gchat")    View.VISIBLE else View.GONE
    }

    // ── Load saved values ─────────────────────────────────────────────────────

    private fun loadSavedValues() {
        binding.switchIncludeSender.isChecked = AppPrefs.includeSender(this)
        binding.editFilterSender.setText(AppPrefs.filterSender(this))

        binding.editEmailRecipient.setText(AppPrefs.emailRecipient(this))
        binding.editSmtpHost.setText(AppPrefs.smtpHost(this))
        binding.editSmtpPort.setText(AppPrefs.smtpPort(this).toString())
        binding.editSmtpUser.setText(AppPrefs.smtpUser(this))
        binding.editSmtpPass.setText(AppPrefs.smtpPass(this))
        binding.switchSmtpSsl.isChecked = AppPrefs.smtpSsl(this)

        binding.editWaNumber.setText(AppPrefs.waNumber(this))
        binding.editGvNumber.setText(AppPrefs.gvNumber(this))

        binding.editTelegramToken.setText(AppPrefs.telegramToken(this))
        binding.editTelegramChatId.setText(AppPrefs.telegramChatId(this))

        binding.editNtfyTopic.setText(AppPrefs.ntfyTopic(this))
        binding.editNtfyServer.setText(AppPrefs.ntfyServer(this))

        binding.editDiscordWebhook.setText(AppPrefs.discordWebhook(this))
        binding.editGChatWebhook.setText(AppPrefs.gchatWebhook(this))
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun saveSettings(shouldFinish: Boolean = true) {
        AppPrefs.prefs(this).edit().apply {
            putString(AppPrefs.KEY_FORWARD_METHOD,   selectedMethod)
            putBoolean(AppPrefs.KEY_INCLUDE_SENDER,  binding.switchIncludeSender.isChecked)
            putString(AppPrefs.KEY_FILTER_SENDER,    binding.editFilterSender.text.toString().trim())
            putString(AppPrefs.KEY_EMAIL_RECIPIENT,  binding.editEmailRecipient.text.toString().trim())
            putString(AppPrefs.KEY_SMTP_HOST,        binding.editSmtpHost.text.toString().trim())
            putString(AppPrefs.KEY_SMTP_PORT,        binding.editSmtpPort.text.toString().trim())
            putString(AppPrefs.KEY_SMTP_USER,        binding.editSmtpUser.text.toString().trim())
            putString(AppPrefs.KEY_SMTP_PASS,        binding.editSmtpPass.text.toString().trim())
            putBoolean(AppPrefs.KEY_SMTP_SSL,         binding.switchSmtpSsl.isChecked)
            putString(AppPrefs.KEY_WA_NUMBER,        binding.editWaNumber.text.toString().trim())
            putString(AppPrefs.KEY_GV_NUMBER,        binding.editGvNumber.text.toString().trim())
            putString(AppPrefs.KEY_TELEGRAM_TOKEN,   binding.editTelegramToken.text.toString().trim())
            putString(AppPrefs.KEY_TELEGRAM_CHAT_ID, binding.editTelegramChatId.text.toString().trim())
            putString(AppPrefs.KEY_NTFY_TOPIC,       binding.editNtfyTopic.text.toString().trim())
            putString(AppPrefs.KEY_NTFY_SERVER,      binding.editNtfyServer.text.toString().trim())
            putString(AppPrefs.KEY_DISCORD_WEBHOOK,  binding.editDiscordWebhook.text.toString().trim())
            putString(AppPrefs.KEY_GCHAT_WEBHOOK,    binding.editGChatWebhook.text.toString().trim())
        }.apply()
        
        if (shouldFinish) {
            showToast("Settings saved ✓")
            finish()
        }
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnGrantPerms.setOnClickListener { requestPermissions() }

        // Test: no enable check — always works so user can verify settings
        binding.btnTestForward.setOnClickListener { sendTestMessage() }

        binding.switchSmtpSsl.setOnCheckedChangeListener { _, useSsl ->
            binding.editSmtpPort.setText(if (useSsl) "465" else "587")
        }
    }

    private fun sendTestMessage() {
        saveSettings(shouldFinish = false)  // auto-save before testing without closing
        val si = Intent(this, ForwardingService::class.java).apply {
            putExtra(SmsReceiver.EXTRA_SENDER,  "+1234567890 (Test)")
            putExtra(SmsReceiver.EXTRA_BODY,    "Test from Relay 🚀\nVerifying SMS forwarding is working correctly.")
            putExtra(SmsReceiver.EXTRA_TIME_MS, System.currentTimeMillis())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si) else startService(si)
        showToast("Test sent via ${selectedMethod.uppercase()}")
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms.toTypedArray()
    }

    private fun requestPermissions() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) showToast("All permissions already granted ✓")
        else permLauncher.launch(needed.toTypedArray())
    }

    private fun updatePermissionBadge() {
        val allGranted = requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        binding.tvPermStatus.text = if (allGranted) "✅ All permissions granted" else "⚠️ Some permissions missing"
        binding.tvPermStatus.setTextColor(
            ContextCompat.getColor(this, if (allGranted) android.R.color.holo_green_dark else android.R.color.holo_orange_dark)
        )
    }

    private fun updateVersionInfo() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersionInfo.text = "Version: ${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvVersionInfo.text = "Version: 1.0.0"
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
