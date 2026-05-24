package com.smsforwarder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smsforwarder.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: SmsDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = SmsDatabase(this)

        setupSwitches()
        setupButtons()
        updateQueueBadge()
    }

    override fun onResume() {
        super.onResume()
        updateQueueBadge()
        binding.switchEnabled.isChecked = AppPrefs.isEnabled(this)
    }

    private fun setupSwitches() {
        binding.switchEnabled.isChecked = AppPrefs.isEnabled(this)
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.prefs(this).edit().putBoolean(AppPrefs.KEY_ENABLED, isChecked).apply()
            showToast(if (isChecked) "Forwarding Active" else "Forwarding Paused")
        }
    }

    private fun setupButtons() {
        binding.btnMenu.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnClearLog.setOnClickListener { confirmClearLog() }
    }

    private fun updateQueueBadge() {
        val count = db.pendingCount()
        binding.tvQueueStatus.text = if (count == 0) "📭 Queue: empty"
                                     else "📬 Queue: $count message(s) waiting"
        binding.tvQueueStatus.setTextColor(
            ContextCompat.getColor(this, if (count == 0) android.R.color.holo_green_dark else android.R.color.holo_orange_dark)
        )
        updateLogView()
    }

    private fun updateLogView() {
        val logs = db.recentLogs(50)
        binding.logContainer.removeAllViews()
        
        if (logs.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No activity yet..."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 14f
                setPadding(0, 40, 0, 0)
                gravity = android.view.Gravity.CENTER
            }
            binding.logContainer.addView(tvEmpty)
            return
        }

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (entry in logs) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = params
            }

            // Header line: [Time] Status | Sender: Name
            val statusIcon = when (entry.status) {
                "sent"   -> "✓"
                "queued" -> "⏳"
                else     -> "✗"
            }
            val headerTv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                text = "[${sdf.format(Date(entry.timestamp))}] $statusIcon Sender: ${entry.sender}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            
            // Message line (2 lines max, natural wrap with ellipsis at the very end)
            val bodyTv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 4
                    bottomMargin = 4
                }
                // Normalize all whitespace to single spaces for a clean multi-line preview
                text = entry.preview.replace(Regex("\\s+"), " ").trim()
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(2f, 1.1f)
            }

            // Footer line: Via Method | Error if any
            val footerTv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                var footerText = "Via: ${entry.method.uppercase()}"
                if (!entry.error.isNullOrBlank()) {
                    footerText += " | Error: ${entry.error}"
                    setTextColor(android.graphics.Color.RED)
                } else {
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                }
                text = footerText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }

            // Divider
            val divider = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = 12
                }
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
            }

            itemLayout.addView(headerTv)
            itemLayout.addView(bodyTv)
            itemLayout.addView(footerTv)
            itemLayout.addView(divider)
            binding.logContainer.addView(itemLayout)
        }
    }

    private fun confirmClearLog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Log")
            .setMessage("Delete all log entries?")
            .setPositiveButton("Clear") { _, _ ->
                db.clearLog()
                showToast("Log cleared")
                updateQueueBadge()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
