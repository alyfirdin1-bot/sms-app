package com.captchapay.smsgateway

import java.util.Locale
import java.util.regex.Pattern

object SmsParser {

    data class ParsedSms(
        val detectedMethod: String?,
        val amount: Double?,
        val senderNumber: String?,
        val trxId: String?,
        val confidence: String
    )

    private fun normalizeDigits(input: String): String {
        val map = mapOf(
            '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
            '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
        )
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(map[ch] ?: ch)
        }
        return sb.toString()
    }

    private fun detectMethod(sender: String, text: String): String? {
        val combined = "$sender $text".lowercase(Locale.US)
        return when {
            combined.contains("bkash") || text.contains("বিকাশ") || combined.contains("*247#") -> "bKash"
            combined.contains("nagad") || text.contains("নগদ") || combined.contains("*167#") -> "Nagad"
            combined.contains("rocket") || text.contains("রকেট") || combined.contains("dbbl") || combined.contains("*322#") -> "Rocket"
            combined.contains("binance") || combined.contains("usdt") || combined.contains("trc20") || combined.contains("bep20") -> "Binance USDT"
            Regex("\\btxnid\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> {
                if (Regex("\\ba/c\\b|tk\\d+", RegexOption.IGNORE_CASE).containsMatchIn(text)) "Rocket" else "Nagad"
            }
            Regex("\\btrxid\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "bKash"
            else -> null
        }
    }

    private fun extractTrxId(raw: String, normalized: String): String? {
        val p = Pattern.compile(
            "(?:trx\\s*id|txn\\s*id|trxn\\s*id|tx\\s*id|transaction\\s*id)(?:\\s*(?:is|no|number|:|#|-))*\\s*([A-Za-z0-9_\\-]{4,64})",
            Pattern.CASE_INSENSITIVE
        )
        val m1 = p.matcher(raw)
        if (m1.find()) {
            return m1.group(1)?.trim()?.uppercase(Locale.US)
        }
        val m2 = p.matcher(normalized)
        if (m2.find()) {
            return m2.group(1)?.trim()?.uppercase(Locale.US)
        }
        return null
    }

    private fun extractAmount(normalized: String): Double? {
        val patterns = listOf(
            Pattern.compile("(?:amount|amt)\\s*(?:is|:|-)?\\s*(?:tk\\.?|bdt|৳)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:send money|sent|payment(?:\\s*of)?|cash[\\s-]*in|received|deposit(?:ed)?)\\s*(?:of|:|-)?\\s*(?:tk\\.?|bdt|৳)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:tk\\.?|bdt|৳)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE)
        )
        for (p in patterns) {
            val m = p.matcher(normalized)
            if (m.find()) {
                val clean = m.group(1)?.replace(",", "") ?: continue
                val num = clean.toDoubleOrNull()
                if (num != null && num >= 1.0 && num <= 1000000.0) {
                    return Math.round(num * 100.0) / 100.0
                }
            }
        }
        return null
    }

    private fun extractSenderNumber(normalized: String): String? {
        val explicit = Pattern.compile(
            "(?:from|sender|payer|a/c)\\s*[:#\\-]?\\s*(?:\\+?88)?(01[3-9]\\d{8,9})(?!\\d)",
            Pattern.CASE_INSENSITIVE
        )
        val m1 = explicit.matcher(normalized)
        if (m1.find()) return m1.group(1)

        val anyPhone = Pattern.compile("(?:\\+?88)?(01[3-9]\\d{8,9})(?!\\d)")
        val m2 = anyPhone.matcher(normalized)
        if (m2.find()) return m2.group(1)
        return null
    }

    fun parse(sender: String, body: String): ParsedSms {
        val normalized = normalizeDigits(body.trim())
        val method = detectMethod(sender, normalized)
        val trxId = extractTrxId(body, normalized)
        val amount = extractAmount(normalized)
        val phone = extractSenderNumber(normalized)

        val coreCount = listOfNotNull(amount, phone, trxId).size
        val hasAny = method != null || coreCount > 0
        val confidence = when {
            coreCount >= 2 -> "high"
            hasAny -> "partial"
            else -> "none"
        }

        return ParsedSms(
            detectedMethod = method,
            amount = amount,
            senderNumber = phone,
            trxId = trxId,
            confidence = confidence
        )
    }
}
