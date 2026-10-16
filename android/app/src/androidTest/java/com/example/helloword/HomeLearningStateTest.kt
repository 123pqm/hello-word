package com.example.helloword

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helloword.api.ApiService
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.*
import kotlinx.coroutines.CompletableDeferred
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class HomeLearningStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val account = "__home_history_test__"
    private val owner = 2_000_000_000
    private val movie = MovieHistoryItem(2_000_000_002, "服务器电影.mp4", "completed", "2026-09-25T10:00:00")

    private class FakeApi(var items: List<MovieHistoryItem>) : ApiService by RetrofitClient.apiService {
        var historyCode = 200
        var reads = 0
        var wordsRead: Int? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun movieHistory(limit: Int, beforeId: Int?): Response<MovieHistoryResponse> {
            check(limit == 1 && beforeId == null)
            reads++
            val snapshot = items
            gate?.let { gate = null; it.await() }
            return if (historyCode == 200) Response.success(MovieHistoryResponse(200, MovieHistoryPage(snapshot, null)))
                else Response.error(historyCode, ResponseBody.create(null, "{}"))
        }
        override suspend fun movieCover(movieId: Int): Response<ResponseBody> =
            Response.error(404, ResponseBody.create(null, "{}"))
        override suspend fun checkin(authorization: String): Response<CheckinResponse> =
            Response.error(503, ResponseBody.create(null, "{}"))
        override suspend fun movieWords(movieId: Int): Response<MovieWordsResponse> {
            wordsRead = movieId
            return Response.success(MovieWordsResponse(200, MovieWords(movieId,
                listOf(MovieWord("shared", "共享的", 1.0, 2.0)))))
        }
        override suspend fun favoriteStatus(word: String, authorization: String): Response<FavoriteStatusResponse> =
            Response.error(503, ResponseBody.create(null, "{}"))
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 7000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        instrumentation.runOnMainSync { assertTrue("Timed out waiting for home history", condition()) }
    }

    private fun text(activity: Activity, id: Int) = activity.findViewById<TextView>(id).text.toString()
    private fun awaitText(activity: Activity, id: Int, expected: String) =
        awaitCondition { text(activity, id) == expected }

    private fun withHome(fake: FakeApi, check: (HomeActivity, VideoUploadStore) -> Unit) {
        val oldAccount = RetrofitClient.account
        val oldToken = RetrofitClient.token
        val oldOwner = RetrofitClient.userId
        val oldHistory = HomeFragment.apiService
        val oldCover = MovieCoverRepository.apiService
        val oldCheckin = CheckinStore.apiService
        val oldWords = MovieWordsRepository.apiService
        val oldFavorites = WordFlashcardActivity.favoritesApi
        lateinit var store: VideoUploadStore
        instrumentation.runOnMainSync {
            RetrofitClient.account = account
            RetrofitClient.userId = owner
            RetrofitClient.token = "test"
            HomeFragment.apiService = fake
            MovieCoverRepository.apiService = fake
            CheckinStore.apiService = fake
            MovieWordsRepository.apiService = fake
            WordFlashcardActivity.favoritesApi = fake
            store = VideoUploadStore.forAccount(context, account)
            store.clear()
            store.select(UploadSession("", "旧的本地草稿.mp4", 100, "00:01:00",
                UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList())))
        }
        var home: HomeActivity? = null
        try {
            home = instrumentation.startActivitySync(Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HomeActivity
            check(home, store)
        } finally {
            instrumentation.runOnMainSync {
                home?.finish()
                store.clear()
                RetrofitClient.account = oldAccount
                RetrofitClient.token = oldToken
                RetrofitClient.userId = oldOwner
                HomeFragment.apiService = oldHistory
                MovieCoverRepository.apiService = oldCover
                CheckinStore.apiService = oldCheckin
                MovieWordsRepository.apiService = oldWords
                WordFlashcardActivity.favoritesApi = oldFavorites
                oldAccount?.let { VideoUploadStore.forAccount(context, it) }
            }
        }
    }

    private fun revisitHome(home: HomeActivity) {
        instrumentation.runOnMainSync { home.findViewById<View>(R.id.navProfile).performClick() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { home.findViewById<View>(R.id.navHome).performClick() }
    }

    @Test fun serverLatestOverridesLocalDraftAndOpensItsFlashcards() {
        val fake = FakeApi(listOf(movie))
        withHome(fake) { home, _ ->
            awaitText(home, R.id.homeRecentTitle, movie.fileName!!)
            awaitText(home, R.id.homeRecentDetails, "上传于 2026-09-25 10:00:00")
            val monitor = instrumentation.addMonitor(WordFlashcardActivity::class.java.name, null, false)
            var card: WordFlashcardActivity? = null
            try {
                instrumentation.runOnMainSync { home.findViewById<View>(R.id.homeRecentLesson).performClick() }
                card = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? WordFlashcardActivity
                assertNotNull(card)
                awaitText(card!!, R.id.tvWord, "shared")
                assertEquals(movie.id, fake.wordsRead)
                // A server deletion is reflected when returning to the home screen.
                instrumentation.runOnMainSync { fake.items = emptyList(); card.finish() }
                awaitText(home, R.id.homeRecentTitle, "暂无最近学习")
            } finally {
                instrumentation.removeMonitor(monitor)
                instrumentation.runOnMainSync { card?.finish() }
            }
        }
    }

    @Test fun emptyAndFailedHistoryNeverFallBackToLocalDraftAndCanRetry() {
        val fake = FakeApi(emptyList())
        withHome(fake) { home, _ ->
            awaitText(home, R.id.homeRecentTitle, "暂无最近学习")
            instrumentation.runOnMainSync { assertFalse(home.findViewById<View>(R.id.homeRecentLesson).isEnabled) }
            instrumentation.runOnMainSync { fake.historyCode = 503 }
            revisitHome(home)
            awaitText(home, R.id.homeRecentTitle, "最近学习加载失败")
            instrumentation.runOnMainSync {
                fake.historyCode = 200
                fake.items = listOf(movie)
                home.findViewById<View>(R.id.homeRecentLesson).performClick()
            }
            awaitText(home, R.id.homeRecentTitle, movie.fileName!!)
        }
    }

    @Test fun pendingMovieRefreshesUntilCompleteWithoutAnUploadSession() {
        val fake = FakeApi(listOf(movie.copy(status = "pending")))
        withHome(fake) { home, store ->
            awaitText(home, R.id.homeLessonProgressLabel, "等待分析")
            instrumentation.runOnMainSync { store.clear(); fake.items = listOf(movie) }
            awaitText(home, R.id.homeLessonProgressLabel, "查看闪卡")
            instrumentation.runOnMainSync { assertTrue(fake.reads >= 2) }
            val newer = movie.copy(id = movie.id + 1, fileName = "另一台手机上传.mp4")
            instrumentation.runOnMainSync { fake.items = listOf(newer) }
            revisitHome(home)
            awaitText(home, R.id.homeRecentTitle, newer.fileName!!)
        }
    }

    @Test fun lateResponseFromPreviousAccountIsDiscarded() {
        val release = CompletableDeferred<Unit>()
        val fake = FakeApi(listOf(movie)).apply { gate = release }
        withHome(fake) { home, _ ->
            awaitCondition { fake.reads == 1 }
            instrumentation.runOnMainSync {
                RetrofitClient.userId = owner + 1
                RetrofitClient.account = "__home_history_second__"
                fake.items = emptyList()
                release.complete(Unit)
            }
            awaitText(home, R.id.homeRecentTitle, "暂无最近学习")
            instrumentation.runOnMainSync { assertTrue(fake.reads >= 2) }
        }
    }

    @Test fun uploadCompletionStillNavigatesOnlyOnce() {
        val fake = FakeApi(listOf(movie.copy(status = "pending")))
        withHome(fake) { home, store ->
            awaitText(home, R.id.homeLessonProgressLabel, "等待分析")
            val monitor = instrumentation.addMonitor(WordFlashcardActivity::class.java.name, null, false)
            var card: WordFlashcardActivity? = null
            try {
                instrumentation.runOnMainSync {
                    fake.items = listOf(movie)
                    store.select(store.state.value!!.copy(phase = UploadPhase.COMPLETED,
                        result = VideoData(movie.fileName!!, "test.mp4", "test.mp4", movie.id, "completed")))
                }
                card = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? WordFlashcardActivity
                assertNotNull(card)
                awaitText(card!!, R.id.tvWord, "shared")
                instrumentation.runOnMainSync { card.finish() }
                awaitText(home, R.id.homeLessonProgressLabel, "查看闪卡")
                SystemClock.sleep(500)
                assertEquals(1, monitor.hits)
                instrumentation.runOnMainSync { assertTrue(store.state.value!!.flashcardsOpened) }
            } finally {
                instrumentation.removeMonitor(monitor)
                instrumentation.runOnMainSync { card?.finish() }
            }
        }
    }
}
