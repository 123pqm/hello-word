package com.example.helloword

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helloword.api.ApiService
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class VideoNotificationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val owner = 2000000000
    private val movieId = 2000000011
    private val account = "__notification_test__"

    private fun waitUntil(label: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            SystemClock.sleep(50)
        }
        instrumentation.runOnMainSync { assertTrue("Notification state did not arrive: $label", check()) }
    }

    @Test fun completionWhileInBackgroundPostsOnceAndHasSafeDestination() {
        org.junit.Assume.assumeTrue(manager.areNotificationsEnabled())
        val oldAccount = RetrofitClient.account
        val oldOwner = RetrofitClient.userId
        val oldToken = RetrofitClient.token
        val oldApi = VideoUploadStore.statusApi
        val completed = CompletableDeferred<Unit>()
        var progressCalled = false
        val prefs = context.getSharedPreferences("video_notifications", android.content.Context.MODE_PRIVATE)
        lateinit var store: VideoUploadStore
        var host: WordFlashcardActivity? = null
        try {
            instrumentation.runOnMainSync {
                RetrofitClient.account = account
                RetrofitClient.userId = owner
                RetrofitClient.token = "test"
                prefs.edit().remove("$owner:$movieId:completed").commit()
                VideoUploadStore.statusApi = object : ApiService by RetrofitClient.apiService {
                    override suspend fun movieProgress(movieId: Int, trackingToken: String): Response<MovieStatusResponse> {
                        assertEquals("task-only-token", trackingToken)
                        progressCalled = true
                        completed.await()
                        return Response.success(MovieStatusResponse(200, MovieStatus(movieId, "completed", null, 12)))
                    }
                    override suspend fun movieStatus(movieId: Int): Response<MovieStatusResponse> =
                        throw AssertionError("Monitoring should use task token instead of expiring login")
                }
                store = VideoUploadStore.forAccount(context, account)
                store.forgetDeletedMovie(movieId)
                store.select(UploadSession("", "通知测试视频.mp4", 100, "01:00", UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList()),
                    phase = UploadPhase.PROCESSING,
                    result = VideoData("test", "test", "test", movieId, "pending", "task-only-token")))
            }
            host = instrumentation.startActivitySync(Intent(context, WordFlashcardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WordFlashcardActivity
            instrumentation.runOnMainSync { assertTrue(VideoProcessingService.start(context)) }
            waitUntil("progress request") { progressCalled }
            waitUntil("ongoing notification") { manager.activeNotifications.any { it.id == VideoNotifications.ONGOING_ID } }
            instrumentation.runOnMainSync { host.moveTaskToBack(true) }
            waitUntil("activity background") { !host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            instrumentation.runOnMainSync { completed.complete(Unit) }
            waitUntil("completion notification") { manager.activeNotifications.any { it.id == movieId && it.tag == "video-result:$owner" } }
            waitUntil("ongoing removed") { manager.activeNotifications.none { it.id == VideoNotifications.ONGOING_ID } }
            val notice = manager.activeNotifications.first { it.id == movieId }
            assertEquals("视频处理完成", notice.notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertNotNull(notice.notification.contentIntent)
            instrumentation.runOnMainSync {
                assertEquals(UploadPhase.COMPLETED, store.state.value!!.phase)
                assertFalse(VideoNotifications.result(context, owner, store.state.value!!, true))
                val incoming = Intent().putExtra(VideoNotifications.EXTRA_OWNER, owner).putExtra(VideoNotifications.EXTRA_MOVIE, movieId)
                val target = VideoNotifications.destination(context, incoming, authenticated = true)
                assertEquals(WordFlashcardActivity::class.java.name, target.component!!.className)
                assertEquals(movieId, target.getIntExtra(WordFlashcardActivity.EXTRA_MOVIE_ID, 0))
                assertEquals(HomeActivity::class.java.name, VideoNotifications.destination(context, incoming, false).component!!.className)
                RetrofitClient.userId = owner - 1
                assertEquals(HomeActivity::class.java.name, VideoNotifications.destination(context, incoming, true).component!!.className)
            }
        } finally {
            instrumentation.runOnMainSync {
                context.stopService(Intent(context, VideoProcessingService::class.java))
                host?.finish()
                store.forgetDeletedMovie(movieId)
                manager.cancel("video-result:$owner", movieId)
                prefs.edit().remove("$owner:$movieId:completed").commit()
                VideoUploadStore.statusApi = oldApi
                RetrofitClient.account = oldAccount
                RetrofitClient.userId = oldOwner
                RetrofitClient.token = oldToken
                oldAccount?.let { VideoUploadStore.forAccount(context, it) }
            }
        }
    }
}
