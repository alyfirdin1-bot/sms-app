package com.captchapay.smsgateway

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
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

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isoUtc(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }

    private fun fsStr(v: String) = JSONObject().put("stringValue", v)
    private fun fsBool(v: Boolean) = JSONObject().put("booleanValue", v)
    private fun fsInt(v: Long) = JSONObject().put("integerValue", v.toString())
    private fun fsDouble(v: Double) = JSONObject().put("doubleValue", v)

    private fun resolveSessionCookie(context: Context, cleanBase: String): String {
        val fromWebView = try {
            val cm = CookieManager.getInstance()
            val hostUrl = try {
                val u = Uri.parse(cleanBase)
                "${u.scheme}://${u.host}"
            } catch (_: Exception) {
                cleanBase
            }
            val c1 = cm.getCookie(cleanBase).orEmpty()
            val c2 = cm.getCookie(hostUrl).orEmpty()
            if (c1.length >= c2.length) c1 else c2
        } catch (_: Exception) {
            ""
        }
        if (fromWebView.isNotBlank()) {
            GatewayConfig.setPreviewCookie(context, fromWebView)
            return fromWebView
        }
        return GatewayConfig.getPreviewCookie(context)
    }

    private fun postJson(
        context: Context,
        baseUrl: String,
        endpointPath: String,
        token: String,
        jsonBody: JSONObject
    ): ApiResult {
        val cleanBase = GatewayConfig.normalizeServerUrl(baseUrl)
        if (!cleanBase.startsWith("https://") && !cleanBase.startsWith("http://")) {
            return ApiResult(false, 0, "Invalid server URL")
        }
        val url = URL("$cleanBase$endpointPath")
        val bodyStr = jsonBody.toString()
        val timestampMs = System.currentTimeMillis().toString()
        val signature = computeHmacSha256(token, "$timestampMs.$bodyStr")
        val sessionCookie = resolveSessionCookie(context, cleanBase)

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 12000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("User-Agent", PreviewLoginActivity.CHROME_MOBILE_UA)
            setRequestProperty("X-API-Key", token)
            setRequestProperty("X-Gateway-Token", token)
            setRequestProperty("X-Gateway-Timestamp", timestampMs)
            setRequestProperty("X-Gateway-Signature", signature)
            if (sessionCookie.isNotBlank()) {
                setRequestProperty("Cookie", sessionCookie)
            }
        }

        return try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(bodyStr)
                writer.flush()
            }
            val code = conn.responseCode
            if (code in 300..399) {
                return ApiResult(false, code, "Redirect ($code)")
            }
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

    private fun postFirestoreCommit(context: Context, writes: JSONArray): ApiResult {
        val baseUrl = GatewayConfig.getFirestoreUrl(context)
        val apiKey = GatewayConfig.getFirebaseApiKey(context)
        val commitUrl = URL("$baseUrl:commit?key=${Uri.encode(apiKey)}")
        val bodyStr = JSONObject().put("writes", writes).toString()

        val conn = (commitUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
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
                ApiResult(false, code, respText.ifBlank { "Firestore HTTP $code" })
            }
        } catch (e: Exception) {
            ApiResult(false, 0, e.message ?: "Firestore network error")
        } finally {
            conn.disconnect()
        }
    }

    private fun postSmsViaFirestore(
        context: Context,
        token: String,
        sender: String,
        rawBody: String,
        receivedAtMs: Long,
        simSlot: Int,
        isTest: Boolean
    ): Pair<ApiResult, SmsParser.ParsedSms> {
        val cleanBody = rawBody.trim().take(2000)
        val cleanSender = sender.trim().take(80).ifEmpty { "Unknown" }
        val parsed = SmsParser.parse(cleanSender, cleanBody)

        val baseDocPath = GatewayConfig.getFirestoreUrl(context).substringAfter("/v1/")
        val smsId = "SMSGW-${receivedAtMs}-${UUID.randomUUID().toString().replace("-", "").take(6).uppercase(Locale.US)}"
        val tsBucket = receivedAtMs / 60_000L
        val smsHash = sha256Hex("$cleanSender|$cleanBody|$tsBucket").take(32)
        val deviceId = GatewayConfig.getDeviceId(context)
        val deviceName = GatewayConfig.getDeviceName(context).take(120)

        val inboxFields = JSONObject().apply {
            put("id", fsStr(smsId))
            put("smsHash", fsStr(smsHash))
            put("receivedAtIso", fsStr(isoUtc(receivedAtMs)))
            put("deviceId", fsStr(deviceId))
            put("deviceName", fsStr(deviceName))
            put("smsSender", fsStr(cleanSender))
            put("simSlot", fsInt(simSlot.toLong()))
            put("rawBody", fsStr(cleanBody))
            put("confidence", fsStr(parsed.confidence))
            put("source", fsStr("android"))
            put("isTest", fsBool(isTest))
            put("apiKey", fsStr(token))
            parsed.detectedMethod?.let { put("detectedMethod", fsStr(it.take(40))) }
            parsed.amount?.let { put("amount", fsDouble(it)) }
            parsed.trxId?.let { put("trxId", fsStr(it.take(64))) }
            parsed.senderNumber?.let { put("senderNumber", fsStr(it.take(40))) }
        }

        val inboxWrite = JSONObject().apply {
            put("update", JSONObject().apply {
                put("name", "$baseDocPath/smsGatewayInbox/$smsId")
                put("fields", inboxFields)
            })
            put("updateTransforms", JSONArray().put(
                JSONObject().put("fieldPath", "createdAt").put("setToServerValue", "REQUEST_TIME")
            ))
        }

        val trxSanitized = parsed.trxId?.uppercase(Locale.US)?.replace(Regex("[^A-Z0-9_\\-]"), "") ?: ""
        val validTrxForLookup = trxSanitized.length in 4..64 && (parsed.amount ?: 0.0) >= 10.0

        if (validTrxForLookup) {
            val lookupFields = JSONObject().apply {
                put("id", fsStr(trxSanitized))
                put("trxId", fsStr(trxSanitized))
                put("smsId", fsStr(smsId))
                put("method", fsStr((parsed.detectedMethod ?: cleanSender).take(40)))
                put("amount", fsDouble(parsed.amount ?: 0.0))
                put("source", fsStr("android"))
                put("isTest", fsBool(isTest))
            }
            val lookupWrite = JSONObject().apply {
                put("update", JSONObject().apply {
                    put("name", "$baseDocPath/smsGatewayTrxLookup/$trxSanitized")
                    put("fields", lookupFields)
                })
                put("updateTransforms", JSONArray().put(
                    JSONObject().put("fieldPath", "createdAt").put("setToServerValue", "REQUEST_TIME")
                ))
            }
            val batchRes = postFirestoreCommit(context, JSONArray().put(inboxWrite).put(lookupWrite))
            if (batchRes.ok) {
                return Pair(ApiResult(true, batchRes.code, smsId), parsed)
            }
        }

        // Fallback commit inbox record alone (e.g., if TrxID already existed in lookup on repeat test)
        val singleRes = postFirestoreCommit(context, JSONArray().put(inboxWrite))
        return Pair(
            if (singleRes.ok) ApiResult(true, singleRes.code, smsId) else singleRes,
            parsed
        )
    }

    private fun sendHeartbeatViaFirestore(context: Context, token: String): ApiResult {
        val baseDocPath = GatewayConfig.getFirestoreUrl(context).substringAfter("/v1/")
        val deviceId = GatewayConfig.getDeviceId(context)
        val deviceName = GatewayConfig.getDeviceName(context).take(120).ifEmpty { "Admin Android Phone" }

        val fields = JSONObject().apply {
            put("id", fsStr(deviceId))
            put("deviceId", fsStr(deviceId))
            put("deviceName", fsStr(deviceName))
            put("model", fsStr("${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120)))
            put("androidVersion", fsStr((Build.VERSION.RELEASE ?: "unknown").take(40)))
            put("batteryPct", fsInt(readBatteryPct(context).toLong()))
            put("isCharging", fsBool(isCharging(context)))
            put("simOperator", fsStr(readSimOperator(context).take(80)))
            put("networkType", fsStr(readNetworkType(context).take(40)))
            put("permissionsGranted", fsBool(hasSmsPermissions(context)))
            put("batteryUnrestricted", fsBool(isIgnoringBatteryOptimizations(context)))
            put("queueDepth", fsInt(GatewayConfig.getPendingQueue(context).size.toLong()))
            put("totalSmsSent", fsInt(GatewayConfig.getTotalSentCount(context).toLong()))
            put("appVersion", fsStr("2.0.0-cloud"))
            put("lastHeartbeatIso", fsStr(isoUtc(System.currentTimeMillis())))
            put("apiKey", fsStr(token))
        }

        val write = JSONObject().apply {
            put("update", JSONObject().apply {
                put("name", "$baseDocPath/smsGatewayDevices/$deviceId")
                put("fields", fields)
            })
            put("updateTransforms", JSONArray().put(
                JSONObject().put("fieldPath", "updatedAt").put("setToServerValue", "REQUEST_TIME")
            ))
        }

        return postFirestoreCommit(context, JSONArray().put(write))
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
            put("appVersion", "2.0.0-cloud")
        }

        executor.execute {
            val serverUrl = GatewayConfig.getServerUrl(appCtx)
            val token = GatewayConfig.getGatewayToken(appCtx)
            if (!GatewayConfig.isConfigured(appCtx)) {
                GatewayConfig.enqueuePendingSms(appCtx, payload)
                GatewayConfig.appendLocalLog(appCtx, "QUEUED (Paste API Key first): $sender")
                notifyUi(appCtx)
                onComplete?.invoke(ApiResult(false, 0, "API Key not set"))
                return@execute
            }

            var res: ApiResult
            var parsedInfo: SmsParser.ParsedSms? = null

            if (!GatewayConfig.isDirectFirestorePrimary(appCtx)) {
                res = postJson(appCtx, serverUrl, "/api/deposit-gateway/ingest", token, payload)
                if (!res.ok) {
                    val fsOut = postSmsViaFirestore(appCtx, token, sender, body, receivedAtMs, simSlot, isTest)
                    res = fsOut.first
                    parsedInfo = fsOut.second
                }
            } else {
                val fsOut = postSmsViaFirestore(appCtx, token, sender, body, receivedAtMs, simSlot, isTest)
                res = fsOut.first
                parsedInfo = fsOut.second
            }

            if (res.ok) {
                GatewayConfig.incrementSentCount(appCtx)
                val logLine = if (parsedInfo != null) {
                    val pMethod = parsedInfo.detectedMethod.orEmpty()
                    val pAmt = parsedInfo.amount?.let { "৳$it" }.orEmpty()
                    val pTrx = parsedInfo.trxId?.let { "TrxID:$it" }.orEmpty()
                    val summary = listOf(pMethod, pAmt, pTrx).filter { it.isNotEmpty() }.joinToString(" • ")
                    if (summary.isNotEmpty()) {
                        "CLOUD 24/7 OK ✓ [$sender] $summary"
                    } else {
                        "CLOUD 24/7 OK ✓ [$sender]: ${body.take(45)}"
                    }
                } else {
                    "SERVER OK ✓ [$sender]: ${body.take(45)}"
                }
                GatewayConfig.appendLocalLog(appCtx, logLine)
                flushPendingQueueSync(appCtx)
            } else {
                val errShort = if (res.message.contains("PERMISSION_DENIED")) {
                    "API Key mismatch — Open Admin SMS Gateway panel once & Paste JSON"
                } else {
                    res.message.take(65)
                }
                if (!isTest) {
                    GatewayConfig.enqueuePendingSms(appCtx, payload)
                    GatewayConfig.appendLocalLog(appCtx, "RETRY QUEUED (${res.code}): $errShort")
                } else {
                    GatewayConfig.appendLocalLog(appCtx, "TEST FAILED (${res.code}): $errShort")
                }
            }
            notifyUi(appCtx)
            onComplete?.invoke(res)
        }
    }

    fun sendHeartbeatAsync(context: Context, onComplete: ((ApiResult) -> Unit)? = null) {
        val appCtx = context.applicationContext
        executor.execute {
            if (!GatewayConfig.isConfigured(appCtx)) {
                onComplete?.invoke(ApiResult(false, 0, "Paste API Key first"))
                return@execute
            }

            flushPendingQueueSync(appCtx)
            val token = GatewayConfig.getGatewayToken(appCtx)
            val serverUrl = GatewayConfig.getServerUrl(appCtx)

            val res = if (!GatewayConfig.isDirectFirestorePrimary(appCtx)) {
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
                    put("appVersion", "2.0.0-cloud")
                }
                val directRes = postJson(appCtx, serverUrl, "/api/deposit-gateway/heartbeat", token, payload)
                if (directRes.ok) directRes else sendHeartbeatViaFirestore(appCtx, token)
            } else {
                sendHeartbeatViaFirestore(appCtx, token)
            }
            onComplete?.invoke(res)
        }
    }

    private fun flushPendingQueueSync(context: Context) {
        if (!GatewayConfig.isConfigured(context)) return
        val pending = GatewayConfig.getPendingQueue(context)
        if (pending.isEmpty()) return

        val token = GatewayConfig.getGatewayToken(context)
        val serverUrl = GatewayConfig.getServerUrl(context)
        val useDirectFs = GatewayConfig.isDirectFirestorePrimary(context)

        val remaining = mutableListOf<JSONObject>()
        var serverDown = false
        for (item in pending) {
            if (serverDown) {
                remaining.add(item)
                continue
            }
            val res = if (!useDirectFs) {
                val directRes = postJson(context, serverUrl, "/api/deposit-gateway/ingest", token, item)
                if (directRes.ok) {
                    directRes
                } else {
                    postSmsViaFirestore(
                        context,
                        token,
                        item.optString("smsSender", "Unknown"),
                        item.optString("rawBody", ""),
                        item.optLong("receivedAtMs", System.currentTimeMillis()),
                        item.optInt("simSlot", -1),
                        item.optBoolean("isTest", false)
                    ).first
                }
            } else {
                postSmsViaFirestore(
                    context,
                    token,
                    item.optString("smsSender", "Unknown"),
                    item.optString("rawBody", ""),
                    item.optLong("receivedAtMs", System.currentTimeMillis()),
                    item.optInt("simSlot", -1),
                    item.optBoolean("isTest", false)
                ).first
            }

            if (res.ok) {
                GatewayConfig.incrementSentCount(context)
            } else if (res.code in 400..499 && res.code != 429) {
                // Drop permanently invalid payload
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
