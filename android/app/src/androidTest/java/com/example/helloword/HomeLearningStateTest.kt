package com.example.helloword

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeLearningStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun awaitText(activity: Activity, id: Int, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var actual = ""
            instrumentation.runOnMainSync { actual = activity.findViewById<TextView>(id)?.text?.toString() ?: "" }
            if (actual == expected) return
            SystemClock.sleep(50)
        }
        fail("Expected text: $expected")
    }

    @Test fun homeUpdatesFromSharedSelectionAndConsumesCompletionOnlyOnce() {
        val account = "__home_learning_test__"
        val movieId = 2_000_000_002
        val oldAccount = RetrofitClient.account
        val oldToken = RetrofitClient.token
        val savedUserId = RetrofitClient.userId
        val savedApi = MovieWordsRepository.apiService
        MovieWordsRepository.apiService = movieApi(MovieWords(movieId, listOf(MovieWord("shared", "共享的", 1.0, 2.0))))
        lateinit var store: VideoUploadStore
        instrumentation.runOnMainSync {
            RetrofitClient.account = account
            RetrofitClient.token = "test"
            RetrofitClient.userId = 2_000_000_000
            store = VideoUploadStore.forAccount(context, account)
            store.clear()
        }
        val monitor = instrumentation.addMonitor(WordFlashcardActivity::class.java.name, null, false)
        var home: HomeActivity? = null
        var card: WordFlashcardActivity? = null
        try {
            home = instrumentation.startActivitySync(Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            awaitText(home, R.id.homeRecentTitle, "暂无最近学习")
            val selected = UploadSession("", "共享电影.mp4", 1048576, "00:01:00",
                UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList()))
            instrumentation.runOnMainSync { store.select(selected) }
            awaitText(home, R.id.homeRecentTitle, "共享电影.mp4")
            awaitText(home, R.id.homeLessonProgressLabel, "待上传")
            // Represents the state saved after a successful status response; no real upload or database writes.
            instrumentation.runOnMainSync {
                store.select(selected.copy(phase = UploadPhase.COMPLETED,
                    result = VideoData("共享电影.mp4", "test.mp4", "test.mp4", movieId, "completed")))
            }
            card = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? WordFlashcardActivity
            assertNotNull("The resumed home Activity must handle the shared event", card)
            awaitText(card!!, R.id.tvWord, "shared")
            instrumentation.runOnMainSync { card.finish() }
            awaitText(home, R.id.homeLessonProgressLabel, "查看闪卡")
            SystemClock.sleep(500)
            assertEquals(1, monitor.hits)
            assertTrue(store.state.value!!.flashcardsOpened)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                card?.finish()
                home?.finish()
                store.clear()
                RetrofitClient.account = oldAccount
                RetrofitClient.token = oldToken
                RetrofitClient.userId = savedUserId
                MovieWordsRepository.apiService = savedApi
                oldAccount?.let { VideoUploadStore.forAccount(context, it) }
            }
        }
    }
}
