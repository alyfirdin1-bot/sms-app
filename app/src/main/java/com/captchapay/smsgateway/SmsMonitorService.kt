package com.captchapay.smsgateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class SmsMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "captchapay_sms_monitor_channel"
        private const val NOTIFICATION_ID = 4041
        private const val HEARTBEAT_INTERVAL_MS = 45_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, SmsMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, SmsMonitorService::class.java)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (GatewayConfig.getPendingCount(applicationContext) > 0) {
                GatewayApiClient.flushPendingQueueAsync(applicationContext) {
                    handler.post { updateNotification() }
                }
            }
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            GatewayApiClient.sendHeartbeatAsync(applicationContext) {
                handler.post { updateNotification() }
            }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
        registerConnectivityFlashRetry()
        if (GatewayConfig.getPendingCount(applicationContext) > 0) {
            SmsFlushWorker.scheduleFlush(applicationContext)
        }
        handler.post(heartbeatRunnable)
    }

    private fun registerConnectivityFlashRetry() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        GatewayConfig.setServiceEnabled(applicationContext, true)
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(heartbeatRunnable)
        if (networkCallbackRegistered) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
            networkCallbackRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val sent = GatewayConfig.getTotalSentCount(applicationContext)
        val queued = GatewayConfig.getPendingCount(applicationContext)
        val statusText = if (GatewayApiClient.hasSmsPermissions(applicationContext)) {
            "24/7 Shield Active • Sent: $sent • SQLite Buffer: $queued"
        } else {
            "SMS Permission Needed! Tap to grant access"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CaptchaPay Deposit SMS Gateway")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}
