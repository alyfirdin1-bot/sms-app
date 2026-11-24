package com.captchapay.smsgateway

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvPermissionSummary: TextView
    private lateinit var tvLogs: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var etGatewayToken: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var swPaymentOnly: Switch

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshUi()
        Toast.makeText(this, "Permissions updated", Toast.LENGTH_SHORT).show()
    }

    private val logUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvPermissionSummary = findViewById(R.id.tvPermissionSummary)
        tvLogs = findViewById(R.id.tvLogs)
        etServerUrl = findViewById(R.id.etServerUrl)
        etGatewayToken = findViewById(R.id.etGatewayToken)
        etDeviceName = findViewById(R.id.etDeviceName)
        swPaymentOnly = findViewById(R.id.swPaymentOnly)

        etServerUrl.setText(GatewayConfig.getServerUrl(this))
        etGatewayToken.setText(GatewayConfig.getGatewayToken(this))
        etDeviceName.setText(GatewayConfig.getDeviceName(this))
        swPaymentOnly.isChecked = GatewayConfig.isPaymentOnly(this)

        findViewById<Button>(R.id.btnGrantPermissions).setOnClickListener {
            requestAllGatewayPermissions()
        }

        findViewById<Button>(R.id.btnBatteryOptimize).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        findViewById<Button>(R.id.btnPasteConfig).setOnClickListener {
            pasteAdminJsonConfig()
        }

        findViewById<Button>(R.id.btnSaveStart).setOnClickListener {
            saveAndStartMonitor()
        }

        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            GatewayConfig.setServiceEnabled(this, false)
            SmsMonitorService.stop(this)
            refreshUi()
            Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTestHeartbeat).setOnClickListener {
            persistInputs()
            GatewayApiClient.sendHeartbeatAsync(this) { res ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (res.ok) "Connected ✓ Server Online" else "Failed (${res.code}): ${res.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshUi()
                }
            }
        }

        findViewById<Button>(R.id.btnTestSms).setOnClickListener {
            persistInputs()
            val sampleSms =
                "You have received Tk 500.00 from 01712345678. Fee Tk 0.00. Balance Tk 2500.00. TrxID TEST${System.currentTimeMillis().toString().takeLast(6)}"
            GatewayApiClient.postSmsAsync(
                context = this,
                sender = "bKash",
                body = sampleSms,
                receivedAtMs = System.currentTimeMillis(),
                simSlot = 1,
                isTest = true
            ) { res ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (res.ok) "Test SMS forwarded ✓" else "Error (${res.code}): ${res.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshUi()
                }
            }
        }

        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(GatewayConfig.ACTION_LOG_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(logUpdateReceiver, filter)
        }
        refreshUi()
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(logUpdateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun persistInputs() {
        GatewayConfig.saveSettings(
            this,
            etServerUrl.text.toString(),
            etGatewayToken.text.toString(),
            etDeviceName.text.toString(),
            swPaymentOnly.isChecked
        )
    }

    private fun saveAndStartMonitor() {
        persistInputs()
        if (!GatewayConfig.isConfigured(this)) {
            Toast.makeText(this, "Enter valid HTTPS Server URL & Gateway Token", Toast.LENGTH_LONG).show()
            return
        }
        if (!GatewayApiClient.hasSmsPermissions(this)) {
            requestAllGatewayPermissions()
            Toast.makeText(this, "Please grant SMS permissions first", Toast.LENGTH_LONG).show()
            return
        }
        GatewayConfig.setServiceEnabled(this, true)
        SmsMonitorService.start(this)
        refreshUi()
        Toast.makeText(this, "24/7 SMS Deposit Monitor Started ✓", Toast.LENGTH_SHORT).show()
    }

    private fun requestAllGatewayPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (GatewayApiClient.isIgnoringBatteryOptimizations(this)) {
            Toast.makeText(this, "Already exempted from battery optimization ✓", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun pasteAdminJsonConfig() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val raw = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val json = JSONObject(raw)
            val url = json.optString("serverUrl").trim()
            val token = json.optString("gatewayToken").trim()
            if (url.isNotEmpty()) etServerUrl.setText(url)
            if (token.isNotEmpty()) etGatewayToken.setText(token)
            Toast.makeText(this, "Admin Gateway Config imported ✓", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Copy Config JSON from Admin Panel first", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshUi() {
        val receiveGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val readGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        val batteryExempt = GatewayApiClient.isIgnoringBatteryOptimizations(this)

        tvServiceStatus.text = if (SmsMonitorService.isRunning) {
            "Status: RUNNING 24/7 • Sent: ${GatewayConfig.getTotalSentCount(this)} • Pending: ${GatewayConfig.getPendingQueue(this).size}"
        } else {
            "Status: STOPPED • Sent: ${GatewayConfig.getTotalSentCount(this)}"
        }

        tvPermissionSummary.text = buildString {
            append("RECEIVE_SMS: ").append(if (receiveGranted) "GRANTED ✓" else "MISSING ✗").append("\n")
            append("READ_SMS: ").append(if (readGranted) "GRANTED ✓" else "MISSING ✗").append("\n")
            append("NOTIFICATIONS: ").append(if (notifGranted) "GRANTED ✓" else "MISSING ✗").append("\n")
            append("BATTERY UNRESTRICTED: ").append(if (batteryExempt) "YES ✓" else "NO (Tap button)")
        }

        tvLogs.text = GatewayConfig.getLocalLogs(this)
    }
}
