package com.captchapay.smsgateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object GatewayApiClient {
    private val executor = Executors.newSingleThreadExecutor()

    data class ApiResult(val ok: Boolean, val code: Int, val message: String)

    private fun computeHmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val raw = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }

    private fun postJson(
        baseUrl: String,
        endpointPath: String,
        token: String,
        jsonBody: JSONObject
    ): ApiResult {
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (!cleanBase.startsWith("https://") && !cleanBase.startsWith("http://")) {
            return ApiResult(false, 0, "Invalid server URL")
        }
        val url = URL("$cleanBase$endpointPath")
        val bodyStr = jsonBody.toString()
        val timestampMs = System.currentTimeMillis().toString()
        val signature = computeHmacSha256(token, "$timestampMs.$bodyStr")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Gateway-Token", token)
            setRequestProperty("X-Gateway-Timestamp", timestampMs)
            setRequestProperty("X-Gateway-Signature", signature)
        }

        return try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(bodyStr)
                writer.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code in 200..299) {
                ApiResult(true, code, respText)
            } else {
                ApiResult(false, code, respText.ifBlank { "HTTP $code" })
            }
        } catch (e: Exception) {
            ApiResult(false, 0, e.message ?: "Network error")
        } finally {
            conn.disconnect()
        }
    }

    fun postSmsAsync(
        context: Context,
        sender: String,
        body: String,
        receivedAtMs: Long,
        simSlot: Int,
        isTest: Boolean = false,
        onComplete: ((ApiResult) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        val payload = JSONObject().apply {
            put("deviceId", GatewayConfig.getDeviceId(appCtx))
            put("deviceName", GatewayConfig.getDeviceName(appCtx))
            put("smsSender", sender)
            put("rawBody", body)
            put("receivedAtMs", receivedAtMs)
            put("simSlot", simSlot)
            put("isTest", isTest)
            put("appVersion", "1.0.0")
        }

        executor.execute {
            val serverUrl = GatewayConfig.getServerUrl(appCtx)
            val token = GatewayConfig.getGatewayToken(appCtx)
            if (!GatewayConfig.isConfigured(appCtx)) {
                GatewayConfig.enqueuePendingSms(appCtx, payload)
                GatewayConfig.appendLocalLog(appCtx, "QUEUED (Not configured): $sender")
                notifyUi(appCtx)
                onComplete?.invoke(ApiResult(false, 0, "Gateway not configured"))
                return@execute
            }

            val res = postJson(serverUrl, "/api/deposit-gateway/ingest", token, payload)
            if (res.ok) {
                GatewayConfig.incrementSentCount(appCtx)
                GatewayConfig.appendLocalLog(appCtx, "SENT ✓ [$sender]: ${body.take(48)}")
                flushPendingQueueSync(appCtx)
            } else {
                GatewayConfig.enqueuePendingSms(appCtx, payload)
                GatewayConfig.appendLocalLog(appCtx, "RETRY QUEUED (${res.code}): ${res.message.take(40)}")
            }
            notifyUi(appCtx)
            onComplete?.invoke(res)
        }
    }

    fun sendHeartbeatAsync(context: Context, onComplete: ((ApiResult) -> Unit)? = null) {
        val appCtx = context.applicationContext
        executor.execute {
            if (!GatewayConfig.isConfigured(appCtx)) {
                onComplete?.invoke(ApiResult(false, 0, "Not configured"))
                return@execute
            }

            flushPendingQueueSync(appCtx)

            val payload = JSONObject().apply {
                put("deviceId", GatewayConfig.getDeviceId(appCtx))
                put("deviceName", GatewayConfig.getDeviceName(appCtx))
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("androidVersion", Build.VERSION.RELEASE ?: "unknown")
                put("batteryPct", readBatteryPct(appCtx))
                put("isCharging", isCharging(appCtx))
                put("simOperator", readSimOperator(appCtx))
                put("networkType", readNetworkType(appCtx))
                put("permissionsGranted", hasSmsPermissions(appCtx))
                put("batteryUnrestricted", isIgnoringBatteryOptimizations(appCtx))
                put("queueDepth", GatewayConfig.getPendingQueue(appCtx).size)
                put("totalSmsSent", GatewayConfig.getTotalSentCount(appCtx))
                put("appVersion", "1.0.0")
            }

            val res = postJson(
                GatewayConfig.getServerUrl(appCtx),
                "/api/deposit-gateway/heartbeat",
                GatewayConfig.getGatewayToken(appCtx),
                payload
            )
            onComplete?.invoke(res)
        }
    }

    private fun flushPendingQueueSync(context: Context) {
        if (!GatewayConfig.isConfigured(context)) return
        val pending = GatewayConfig.getPendingQueue(context)
        if (pending.isEmpty()) return

        val remaining = mutableListOf<JSONObject>()
        var serverDown = false
        for (item in pending) {
            if (serverDown) {
                remaining.add(item)
                continue
            }
            val res = postJson(
                GatewayConfig.getServerUrl(context),
                "/api/deposit-gateway/ingest",
                GatewayConfig.getGatewayToken(context),
                item
            )
            if (res.ok) {
                GatewayConfig.incrementSentCount(context)
            } else if (res.code in 400..499 && res.code != 429) {
                // Drop permanently malformed payload
            } else {
                remaining.add(item)
                serverDown = true
            }
        }
        GatewayConfig.replacePendingQueue(context, remaining)
    }

    private fun notifyUi(context: Context) {
        context.sendBroadcast(Intent(GatewayConfig.ACTION_LOG_UPDATED).setPackage(context.packageName))
    }

    fun hasSmsPermissions(context: Context): Boolean {
        val receiveOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED
        val readOk = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        return receiveOk && readOk
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun readBatteryPct(context: Context): Int {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    private fun isCharging(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return plugged != 0
    }

    private fun readSimOperator(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.simOperatorName ?: "Unknown SIM"
        } catch (_: Exception) {
            "Unknown SIM"
        }
    }

    private fun readNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            val net = cm.activeNetwork ?: return "offline"
            val caps = cm.getNetworkCapabilities(net) ?: return "offline"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
                else -> "Online"
            }
        } catch (_: Exception) {
            "Online"
        }
    }
}
