package com.example.helloword

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.UploadSession

object VideoNotifications {
    const val ONGOING_ID = -10001
    const val EXTRA_OWNER = "notification_movie_owner"
    const val EXTRA_MOVIE = "notification_movie_id"
    const val RESULTS = "video_processing_results"
    private const val PROGRESS = "video_processing_progress"

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(PROGRESS, "视频处理进度", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(RESULTS, "视频处理结果", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun contentIntent(context: Context, owner: Int, movieId: Int): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("helloword://video-result/$owner/$movieId")
            putExtra(EXTRA_OWNER, owner)
            putExtra(EXTRA_MOVIE, movieId)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun destination(context: Context, incoming: Intent, authenticated: Boolean = RetrofitClient.hasValidSession()): Intent {
        val movieId = incoming.getIntExtra(EXTRA_MOVIE, 0)
        val owner = incoming.getIntExtra(EXTRA_OWNER, 0)
        return if (movieId > 0 && owner == RetrofitClient.userId && authenticated) {
            RetrofitClient.account?.let { VideoUploadStore.forAccount(context, it).markFlashcardsOpened(movieId) }
            Intent(context, WordFlashcardActivity::class.java).putExtra(WordFlashcardActivity.EXTRA_MOVIE_ID, movieId)
        } else Intent(context, HomeActivity::class.java)
    }

    fun ongoing(context: Context, owner: Int, movieId: Int, uploading: Boolean): Notification {
        channels(context)
        return NotificationCompat.Builder(context, PROGRESS)
            .setSmallIcon(R.drawable.ic_video_notification)
            .setContentTitle(if (uploading) "正在上传视频" else "视频处理中")
            .setContentText("可切换到其他应用，处理完成后会通知你")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent(context, owner, 0))
            .build()
    }

    @Synchronized
    fun result(context: Context, owner: Int, session: UploadSession, completed: Boolean): Boolean {
        val movieId = session.result?.movieId ?: return false
        val key = "$owner:$movieId:${if (completed) "completed" else "failed"}"
        val prefs = context.getSharedPreferences("video_notifications", Context.MODE_PRIVATE)
        if (prefs.getBoolean(key, false)) return false
        channels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(RESULTS)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val title = if (completed) "视频处理完成" else "视频处理未完成"
        val notification = NotificationCompat.Builder(context, RESULTS)
            .setSmallIcon(R.drawable.ic_video_notification)
            .setContentTitle(title).setContentText("${session.name} · ${session.message}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${session.name}\n${session.message}"))
            .setContentIntent(contentIntent(context, owner, if (completed) movieId else 0))
            .setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL).build()
        return try {
            manager.notify("video-result:$owner", movieId, notification)
            prefs.edit().putBoolean(key, true).commit()
            true
        } catch (_: SecurityException) { false }
    }

    fun clearResults(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("video-result:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelMovie(context: Context, owner: Int, movieId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel("video-result:$owner", movieId)
    }
}
