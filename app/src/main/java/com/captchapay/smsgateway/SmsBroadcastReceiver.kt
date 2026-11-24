package com.captchapay.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

class SmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val extras = intent.extras ?: return
        val messages: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val simSlot = extras.getInt("slot", extras.getInt("subscription", -1))

        // Group multipart PDUs by sender address
        val grouped = linkedMapOf<String, StringBuilder>()
        var latestTimestamp = System.currentTimeMillis()

        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: sms.originatingAddress ?: "Unknown"
            val bodyPart = sms.displayMessageBody ?: sms.messageBody ?: ""
            latestTimestamp = sms.timestampMillis.takeIf { it > 0 } ?: latestTimestamp
            grouped.getOrPut(sender) { StringBuilder() }.append(bodyPart)
        }

        val paymentOnly = GatewayConfig.isPaymentOnly(context)

        for ((sender, bodyBuilder) in grouped) {
            val fullBody = bodyBuilder.toString().trim()
            if (fullBody.isEmpty()) continue

            if (paymentOnly && !GatewayConfig.isPaymentSms(sender, fullBody)) {
                GatewayConfig.appendLocalLog(context, "SKIPPED (Non-payment): $sender")
                context.sendBroadcast(
                    Intent(GatewayConfig.ACTION_LOG_UPDATED).setPackage(context.packageName)
                )
                continue
            }

            GatewayApiClient.postSmsAsync(
                context = context,
                sender = sender,
                body = fullBody,
                receivedAtMs = latestTimestamp,
                simSlot = simSlot,
                isTest = false
            )
        }
    }
}
