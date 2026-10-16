package com.example.helloword

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helloword.api.ApiService
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.*
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

@RunWith(AndroidJUnit4::class)
class MyMoviesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val movie = MovieHistoryItem(2000000003, "测试电影.mp4", "completed", "2026-09-23")

    private class FakeApi(val movie: MovieHistoryItem) : ApiService by RetrofitClient.apiService {
        var items = listOf(movie)
        var deleteCode = 204
        var historyCode = 200
        var deletes = 0
        var reads = 0
        var cursor: Int? = null
        val requestedCursors = mutableListOf<Int?>()
        override suspend fun movieHistory(limit: Int, beforeId: Int?): Response<MovieHistoryResponse> {
            reads++
            requestedCursors.add(beforeId)
            return if (historyCode == 200) Response.success(MovieHistoryResponse(200, MovieHistoryPage(items, cursor)))
                else Response.error(historyCode, ResponseBody.create(null, "{}"))
        }
        override suspend fun deleteMovie(movieId: Int): Response<Unit> {
            check(movieId == movie.id)
            deletes++
            return if (deleteCode == 204) { items = emptyList(); Response.success<Unit>(204, null) }
                else Response.error(deleteCode, ResponseBody.create(null, "{}"))
        }
        override suspend fun movieCover(movieId: Int): Response<ResponseBody> =
            Response.error(404, ResponseBody.create(null, "{}"))
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < end) {
            var done = false
            instrumentation.runOnMainSync { done = condition() }
            if (done) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(50)
        }
        instrumentation.runOnMainSync { assertTrue("Timed out waiting for movie UI", condition()) }
    }

    private fun withMovies(check: (MyMoviesActivity, FakeApi) -> Unit) {
        val oldApi = MyMoviesActivity.apiService
        val oldCoverApi = MovieCoverRepository.apiService
        val oldAccount = RetrofitClient.account
        val oldUser = RetrofitClient.userId
        val oldToken = RetrofitClient.token
        val fake = FakeApi(movie)
        instrumentation.runOnMainSync {
            MyMoviesActivity.apiService = fake
            MovieCoverRepository.apiService = fake
            RetrofitClient.account = "__my_movies_test__"
            RetrofitClient.userId = 2000000000
            RetrofitClient.token = "test"
        }
        var activity: MyMoviesActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MyMoviesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MyMoviesActivity
            awaitCondition { activity.findViewById<RecyclerView>(R.id.movieRecyclerView).adapter?.itemCount == 1 }
            check(activity, fake)
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                VideoUploadStore.forAccount(context, "__my_movies_test__").forgetDeletedMovie(movie.id)
                MyMoviesActivity.apiService = oldApi
                MovieCoverRepository.apiService = oldCoverApi
                RetrofitClient.account = oldAccount
                RetrofitClient.userId = oldUser
                RetrofitClient.token = oldToken
                oldAccount?.let { VideoUploadStore.forAccount(context, it) }
            }
        }
    }

    @Test fun cancelFailureAndConfirmedDeletionKeepServerAndListConsistent() = withMovies { activity, fake ->
        instrumentation.runOnMainSync {
            LocalMovieFiles(context, "__my_movies_test__").remember(movie.id, android.net.Uri.parse("content://test/movie"))
        }
        onView(withId(R.id.deleteMovie)).perform(click())
        onView(withText("取消")).perform(click())
        assertEquals(0, fake.deletes)
        fake.deleteCode = 503
        onView(withId(R.id.deleteMovie)).perform(click())
        onView(withId(android.R.id.button1)).perform(click())
        awaitCondition { activity.findViewById<TextView>(R.id.historyMessage).text.startsWith("删除失败") }
        instrumentation.runOnMainSync {
            assertEquals(1, activity.findViewById<RecyclerView>(R.id.movieRecyclerView).adapter!!.itemCount)
            assertNotNull(LocalMovieFiles(context, "__my_movies_test__").find(movie.id))
        }
        fake.deleteCode = 204
        onView(withId(R.id.deleteMovie)).perform(click())
        onView(withId(android.R.id.button1)).perform(click())
        awaitCondition { activity.findViewById<RecyclerView>(R.id.movieRecyclerView).adapter?.itemCount == 0 }
        instrumentation.runOnMainSync {
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyView).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.historyLoadMore).visibility)
            assertNull(LocalMovieFiles(context, "__my_movies_test__").find(movie.id))
        }
        val reads = fake.reads
        onView(withId(R.id.historyRefresh)).perform(swipeDown())
        awaitCondition { fake.reads > reads && !activity.findViewById<SwipeRefreshLayout>(R.id.historyRefresh).isRefreshing }
        assertEquals(2, fake.deletes)
    }

    @Test fun pullToRefreshRetainsRowsOnErrorAndResetsPagination() = withMovies { activity, fake ->
        instrumentation.runOnMainSync { assertEquals(View.GONE, activity.findViewById<View>(R.id.historyLoadMore).visibility) }
        fake.historyCode = 503
        onView(withId(R.id.historyRefresh)).perform(swipeDown())
        awaitCondition { activity.findViewById<TextView>(R.id.historyMessage).text.startsWith("读取失败") }
        instrumentation.runOnMainSync { assertEquals(1, activity.findViewById<RecyclerView>(R.id.movieRecyclerView).adapter!!.itemCount) }
        fake.historyCode = 200
        fake.cursor = movie.id
        onView(withId(R.id.historyRefresh)).perform(swipeDown())
        awaitCondition { activity.findViewById<View>(R.id.historyLoadMore).visibility == View.VISIBLE }
        fake.items = listOf(movie.copy(id = movie.id - 1))
        fake.cursor = null
        onView(withId(R.id.historyLoadMore)).perform(click())
        awaitCondition { activity.findViewById<RecyclerView>(R.id.movieRecyclerView).adapter?.itemCount == 2 }
        assertEquals(movie.id, fake.requestedCursors.last())
        fake.items = listOf(movie)
        onView(withId(R.id.historyRefresh)).perform(swipeDown())
        awaitCondition { activity.findViewById<RecyclerView>(R.id.movieRecyclerView).adapter?.itemCount == 1 }
        assertNull(fake.requestedCursors.last())
    }
}
