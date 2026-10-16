package com.example.helloword

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.UploadPhase
import kotlinx.coroutines.*

/** User-started foreground monitoring keeps the existing retained upload and polling alive. */
class VideoProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitoring: Job? = null

    companion object {
        fun start(context: Context): Boolean {
            val account = RetrofitClient.account ?: return false
            val owner = RetrofitClient.userId ?: return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, VideoProcessingService::class.java)
                    .putExtra("account", account).putExtra("owner", owner))
                true
            } catch (error: RuntimeException) {
                android.util.Log.w("VideoReminder", "Cannot start monitoring: ${error.javaClass.simpleName}")
                false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val account = intent?.getStringExtra("account")
        val owner = intent?.getIntExtra("owner", 0) ?: 0
        try {
            startForeground(VideoNotifications.ONGOING_ID, VideoNotifications.ongoing(this, owner, 0, false))
        } catch (error: RuntimeException) {
            android.util.Log.w("VideoReminder", "Cannot enter foreground: ${error.javaClass.simpleName}")
            stopSelf(); return START_NOT_STICKY
        }
        if (account == null || owner <= 0 || account != RetrofitClient.account || owner != RetrofitClient.userId) {
            finishMonitoring(); return START_NOT_STICKY
        }
        monitoring?.cancel()
        monitoring = scope.launch {
            val store = VideoUploadStore.forAccount(this@VideoProcessingService, account)
            launch { store.observeProcessing() }
            launch {
                while (isActive) {
                    if (account != RetrofitClient.account || owner != RetrofitClient.userId) { finishMonitoring(); return@launch }
                    delay(1000)
                }
            }
            store.state.collect { session ->
                if (account != RetrofitClient.account || owner != RetrofitClient.userId) { finishMonitoring(); return@collect }
                when (session?.phase) {
                    UploadPhase.UPLOADING, UploadPhase.PROCESSING -> {
                        val notification = VideoNotifications.ongoing(this@VideoProcessingService, owner,
                            session.result?.movieId ?: 0, session.phase == UploadPhase.UPLOADING)
                        // 拒绝通知权限仍允许前台服务运行，但不能普通发通知。
                        if (androidx.core.app.NotificationManagerCompat.from(this@VideoProcessingService).areNotificationsEnabled()) {
                            try {
                                getSystemService(android.app.NotificationManager::class.java).notify(VideoNotifications.ONGOING_ID, notification)
                            } catch (_: SecurityException) { /* Permission may change while running. */ }
                        }
                    }
                    UploadPhase.COMPLETED -> {
                        VideoNotifications.result(this@VideoProcessingService, owner, session, true)
                        finishMonitoring()
                    }
                    UploadPhase.PROCESS_FAILED, UploadPhase.UNAVAILABLE -> {
                        VideoNotifications.result(this@VideoProcessingService, owner, session, false)
                        finishMonitoring()
                    }
                    else -> finishMonitoring()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun finishMonitoring() {
        monitoring?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) { finishMonitoring() }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
