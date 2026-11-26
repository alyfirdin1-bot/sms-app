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
    private const val KEY_PREVIEW_COOKIE = "preview_session_cookie"
    private const val KEY_FIRESTORE_URL = "firestore_url"
    private const val KEY_FIREBASE_API_KEY = "firebase_api_key"
    private const val KEY_STRICT_SENDER_WHITELIST = "strict_sender_whitelist"
    private const val KEY_TRUSTED_SENDERS = "trusted_senders_csv"

    const val DEFAULT_TRUSTED_SENDERS_CSV =
        "BKASH,NAGAD,16216,ROCKET,DBBL,UPAY,CELLFIN,BINANCE,TAP,OKWALLET"

    const val DEFAULT_FIRESTORE_URL =
        "https://firestore.googleapis.com/v1/projects/gen-lang-client-0557312943/databases/ai-studio-taskearnhub-f7292225-6c41-41e6-a48d-cf09d4917289/documents"
    const val DEFAULT_FIREBASE_API_KEY = "AIzaSyBfK91sQ1VVxsopLh6l8Astaz3n0hOOmHk"

    const val ACTION_LOG_UPDATED = "com.captchapay.smsgateway.ACTION_LOG_UPDATED"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun normalizeServerUrl(raw: String): String {
        var clean = raw.trim().trimEnd('/')
        val apiIdx = clean.indexOf("/api/deposit-gateway")
        if (apiIdx > 0) {
            clean = clean.substring(0, apiIdx).trimEnd('/')
        }
        return clean
    }

    fun getServerUrl(context: Context): String =
        normalizeServerUrl(prefs(context).getString(KEY_SERVER_URL, "") ?: "")

    fun getFirestoreUrl(context: Context): String =
        (prefs(context).getString(KEY_FIRESTORE_URL, DEFAULT_FIRESTORE_URL) ?: DEFAULT_FIRESTORE_URL)
            .trim()
            .trimEnd('/')
            .ifEmpty { DEFAULT_FIRESTORE_URL }

    fun getFirebaseApiKey(context: Context): String =
        (prefs(context).getString(KEY_FIREBASE_API_KEY, DEFAULT_FIREBASE_API_KEY) ?: DEFAULT_FIREBASE_API_KEY)
            .trim()
            .ifEmpty { DEFAULT_FIREBASE_API_KEY }

    fun setFirestoreConnection(context: Context, firestoreUrl: String, firebaseApiKey: String) {
        val editor = prefs(context).edit()
        if (firestoreUrl.trim().startsWith("https://")) {
            editor.putString(KEY_FIRESTORE_URL, firestoreUrl.trim().trimEnd('/'))
        }
        if (firebaseApiKey.trim().isNotEmpty()) {
            editor.putString(KEY_FIREBASE_API_KEY, firebaseApiKey.trim())
        }
        editor.apply()
    }

    fun getGatewayToken(context: Context): String =
        (prefs(context).getString(KEY_GATEWAY_TOKEN, "") ?: "").trim()

    fun getPreviewCookie(context: Context): String =
        (prefs(context).getString(KEY_PREVIEW_COOKIE, "") ?: "").trim()

    fun setPreviewCookie(context: Context, cookie: String) {
        prefs(context).edit().putString(KEY_PREVIEW_COOKIE, cookie.trim()).apply()
    }

    fun getDeviceId(context: Context): String {
        val p = prefs(context)
        var id = p.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = "android-${UUID.randomUUID().toString().take(12)}"
            p.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id.replace(Regex("[^a-zA-Z0-9_\\-]"), "-")
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
            .putString(KEY_SERVER_URL, normalizeServerUrl(serverUrl))
            .putString(KEY_GATEWAY_TOKEN, token.trim())
            .putString(KEY_DEVICE_NAME, deviceName.trim().ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}" })
            .putBoolean(KEY_PAYMENT_ONLY, paymentOnly)
            .apply()
    }

    fun isDirectFirestorePrimary(context: Context): Boolean {
        val url = getServerUrl(context)
        return url.isEmpty() || url.contains("ais-dev-") || url.contains("ais-pre-")
    }

    fun isConfigured(context: Context): Boolean {
        val token = getGatewayToken(context)
        return token.length >= 16 && getFirestoreUrl(context).startsWith("https://")
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

    fun isStrictSenderWhitelist(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STRICT_SENDER_WHITELIST, true)

    fun setStrictSenderWhitelist(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRICT_SENDER_WHITELIST, enabled).apply()
    }

    fun getTrustedSenders(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_TRUSTED_SENDERS, DEFAULT_TRUSTED_SENDERS_CSV)
            ?: DEFAULT_TRUSTED_SENDERS_CSV
        val list = raw.split(",")
            .map { it.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9_\\-]"), "") }
            .filter { it.length in 2..32 }
            .distinct()
        return if (list.isNotEmpty()) list else DEFAULT_TRUSTED_SENDERS_CSV.split(",")
    }

    fun setTrustedSenders(context: Context, senders: List<String>) {
        val cleaned = senders
            .map { it.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9_\\-]"), "") }
            .filter { it.length in 2..32 }
            .distinct()
            .take(40)
        if (cleaned.isNotEmpty()) {
            prefs(context).edit().putString(KEY_TRUSTED_SENDERS, cleaned.joinToString(",")).apply()
        }
    }

    fun isPersonalPhoneSender(sender: String): Boolean {
        val compact = sender.trim().replace(Regex("[\\s\\-()]"), "")
        if (compact.isEmpty()) return true
        if (Regex("^(\\+?88)?01[3-9]\\d{8,9}$").matches(compact)) return true
        if (Regex("^\\+?\\d{7,16}$").matches(compact)) return true
        return false
    }

    fun isTrustedPaymentSender(context: Context, sender: String): Pair<Boolean, String?> {
        if (!isStrictSenderWhitelist(context)) return Pair(true, null)
        val raw = sender.trim()
        val normalized = raw.uppercase(Locale.US).replace(Regex("[^A-Z0-9_\\-]"), "")
        val whitelist = getTrustedSenders(context)

        if (isPersonalPhoneSender(raw)) {
            val digitsOnly = raw.replace(Regex("\\D"), "")
            val isShortcode = digitsOnly.length <= 6 && whitelist.contains(digitsOnly)
            if (!isShortcode) {
                return Pair(false, "Spoofing blocked: personal phone number ($raw)")
            }
        }
        if (normalized.isEmpty()) {
            return Pair(false, "Spoofing blocked: empty sender ID")
        }
        val matched = whitelist.any { allowed ->
            normalized == allowed || normalized.contains(allowed)
        }
        if (!matched) {
            return Pair(false, "Spoofing blocked: untrusted sender ($raw)")
        }
        return Pair(true, null)
    }

    fun getPendingCount(context: Context): Int =
        OfflineSmsDb.getInstance(context).getPendingCount()

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
