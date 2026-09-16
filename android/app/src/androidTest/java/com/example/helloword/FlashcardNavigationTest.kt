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
class FlashcardNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun awaitText(activity: Activity, id: Int, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var actual = ""
            instrumentation.runOnMainSync { actual = activity.findViewById<TextView>(id).text.toString() }
            if (actual == expected) return
            SystemClock.sleep(50)
        }
        instrumentation.runOnMainSync {
            assertEquals(expected, activity.findViewById<TextView>(id).text.toString())
        }
    }

    private fun withCompletedMovie(words: List<MovieWord>, check: (movie_upload, WordFlashcardActivity) -> Unit) {
        val movieId = 2_000_000_001
        val account = "__flashcard_test__"
        val previousAccount = RetrofitClient.account
        val previousToken = RetrofitClient.token
        val cache = File(context.filesDir, "movie_flashcards/$movieId.json")
        val previousCache = if (cache.exists()) cache.readBytes() else null
        cache.parentFile!!.mkdirs()
        cache.writeText(Gson().toJson(MovieWords(movieId, words)))
        lateinit var store: VideoUploadStore
        instrumentation.runOnMainSync {
            RetrofitClient.account = account
            RetrofitClient.token = "test"
            store = VideoUploadStore.forAccount(context, account)
            store.clear()
            store.select(UploadSession("", "test.mp4", 100, "00:01:00",
                UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList()),
                phase = UploadPhase.COMPLETED,
                result = VideoData("test.mp4", "test.mp4", "test.mp4", movieId, "pending")))
        }
        val monitor = instrumentation.addMonitor(WordFlashcardActivity::class.java.name, null, false)
        var upload: movie_upload? = null
        var flashcard: WordFlashcardActivity? = null
        try {
            upload = instrumentation.startActivitySync(Intent(context, movie_upload::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(UploadVocabulary.EXTRA_SOURCE, UploadVocabulary.SOURCE_BOOK)
            }) as movie_upload
            flashcard = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? WordFlashcardActivity
            assertNotNull("Completed upload must automatically open the flashcard Activity", flashcard)
            check(upload, flashcard!!)
            instrumentation.runOnMainSync { flashcard.finish() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            assertEquals("Returning must not automatically reopen the same result", 1, monitor.hits)
            assertTrue(store.state.value!!.flashcardsOpened)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                flashcard?.finish()
                upload?.finish()
                store.clear()
                RetrofitClient.account = previousAccount
                RetrofitClient.token = previousToken
            }
            if (previousCache == null) cache.delete() else cache.writeBytes(previousCache)
        }
    }

    @Test fun completedUploadOpensRealWordsAndNextChangesCard() {
        withCompletedMovie(listOf(MovieWord("movie", "电影", 1.25, 1.8), MovieWord("world", "世界", 7.0, 7.5))) { _, flashcard ->
            awaitText(flashcard, R.id.tvWord, "movie")
            awaitText(flashcard, R.id.tvMeaning, "电影")
            awaitText(flashcard, R.id.tvFlashcardProgress, "1 / 2")
            instrumentation.runOnMainSync { flashcard.findViewById<TextView>(R.id.btnNext).performClick() }
            awaitText(flashcard, R.id.tvWord, "world")
            awaitText(flashcard, R.id.tvMeaning, "世界")
            awaitText(flashcard, R.id.btnNext, "完成学习")
        }
    }

    @Test fun emptyCompletedResultOpensEmptyStateWithoutSampleWords() {
        withCompletedMovie(emptyList()) { _, flashcard ->
            awaitText(flashcard, R.id.tvWord, "暂无匹配单词")
            awaitText(flashcard, R.id.btnNext, "返回")
            instrumentation.runOnMainSync { assertFalse(flashcard.findViewById<TextView>(R.id.btnKnown).isEnabled) }
        }
    }
}
