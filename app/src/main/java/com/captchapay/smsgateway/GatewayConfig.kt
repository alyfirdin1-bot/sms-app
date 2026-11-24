package com.captchapay.smsgateway

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object GatewayConfig {
    private const val PREF_NAME = "captchapay_sms_gateway_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_GATEWAY_TOKEN = "gateway_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_PAYMENT_ONLY = "payment_only"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_PENDING_QUEUE = "pending_sms_queue"
    private const val KEY_LOCAL_LOGS = "local_sms_logs"
    private const val KEY_TOTAL_SENT = "total_sent_count"

    const val ACTION_LOG_UPDATED = "com.captchapay.smsgateway.ACTION_LOG_UPDATED"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(context: Context): String =
        (prefs(context).getString(KEY_SERVER_URL, "") ?: "").trim().trimEnd('/')

    fun getGatewayToken(context: Context): String =
        (prefs(context).getString(KEY_GATEWAY_TOKEN, "") ?: "").trim()

    fun getDeviceId(context: Context): String {
        val p = prefs(context)
        var id = p.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = "android-${UUID.randomUUID().toString().take(12)}"
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getDeviceName(context: Context): String {
        val saved = prefs(context).getString(KEY_DEVICE_NAME, null)
        if (!saved.isNullOrBlank()) return saved
        return "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    fun isPaymentOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAYMENT_ONLY, true)

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, false)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    fun saveSettings(
        context: Context,
        serverUrl: String,
        token: String,
        deviceName: String,
        paymentOnly: Boolean
    ) {
        prefs(context).edit()
            .putString(KEY_SERVER_URL, serverUrl.trim().trimEnd('/'))
            .putString(KEY_GATEWAY_TOKEN, token.trim())
            .putString(KEY_DEVICE_NAME, deviceName.trim().ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}" })
            .putBoolean(KEY_PAYMENT_ONLY, paymentOnly)
            .apply()
    }

    fun isConfigured(context: Context): Boolean {
        val url = getServerUrl(context)
        val token = getGatewayToken(context)
        return (url.startsWith("https://") || url.startsWith("http://")) && token.length >= 12
    }

    fun incrementSentCount(context: Context): Int {
        val p = prefs(context)
        val next = p.getInt(KEY_TOTAL_SENT, 0) + 1
        p.edit().putInt(KEY_TOTAL_SENT, next).apply()
        return next
    }

    fun getTotalSentCount(context: Context): Int =
        prefs(context).getInt(KEY_TOTAL_SENT, 0)

    @Synchronized
    fun enqueuePendingSms(context: Context, payload: JSONObject) {
        val p = prefs(context)
        val raw = p.getString(KEY_PENDING_QUEUE, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        arr.put(payload)
        // Keep bounded to last 200 items
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - 200)
        for (i in start until arr.length()) {
            trimmed.put(arr.get(i))
        }
        p.edit().putString(KEY_PENDING_QUEUE, trimmed.toString()).apply()
    }

    @Synchronized
    fun getPendingQueue(context: Context): List<JSONObject> {
        val raw = prefs(context).getString(KEY_PENDING_QUEUE, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(it) }
        }
        return list
    }

    @Synchronized
    fun replacePendingQueue(context: Context, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_PENDING_QUEUE, arr.toString()).apply()
    }

    @Synchronized
    fun appendLocalLog(context: Context, line: String) {
        val p = prefs(context)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val existing = p.getString(KEY_LOCAL_LOGS, "") ?: ""
        val lines = existing.split("\n").filter { it.isNotBlank() }.toMutableList()
        lines.add(0, "[$ts] $line")
        val capped = lines.take(35).joinToString("\n")
        p.edit().putString(KEY_LOCAL_LOGS, capped).apply()
    }

    fun getLocalLogs(context: Context): String =
        prefs(context).getString(KEY_LOCAL_LOGS, "No SMS captured yet.") ?: "No SMS captured yet."

    fun isPaymentSms(sender: String, body: String): Boolean {
        val combined = "$sender $body".lowercase(Locale.getDefault())
        val keywords = listOf(
            "bkash", "nagad", "rocket", "upay", "dbbl", "cellfin",
            "binance", "usdt", "trxid", "txnid", "trxnid", "txid",
            "cash in", "cash-in", "send money", "received tk", "received bdt",
            "payment", "টাকা", "বিকাশ", "নগদ", "রকেট", "ট্রানজেকশন"
        )
        return keywords.any { combined.contains(it) }
    }
}
