package com.example.helloword

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.matcher.ViewMatchers.*
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
import java.io.File

@RunWith(AndroidJUnit4::class)
class FavoritesActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private class FakeApi : ApiService by RetrofitClient.apiService {
        val words = mutableListOf(
            FavoriteWord(3, "movie", "电影", "It's a wonderful movie.", 26, "Example.mp4", 1.0, 2.0, "2026-09-23"),
            FavoriteWord(2, "beautiful", "美丽的；漂亮的", "What a beautiful day!", 26, "Example.mp4", 3.0, 4.0, "2026-09-23"),
            FavoriteWord(1, "world", "世界", null, null, null, null, null, "2026-09-23"))
        var failRead = false
        var failDelete = false
        var deleteCalls = 0
        var readCalls = 0
        override suspend fun favoriteWords(authorization: String, limit: Int, beforeId: Long?): Response<FavoritesResponse> {
            readCalls++
            if (failRead) return Response.error(503, ResponseBody.create(null, "{}"))
            val page = words.filter { beforeId == null || it.id < beforeId }
            val items = page.take(2)
            return Response.success(FavoritesResponse(200, FavoritePage(items, if (page.size > 2) items.last().id else null)))
        }
        override suspend fun deleteFavorite(word: String, authorization: String): Response<Unit> {
            deleteCalls++
            if (failDelete) return Response.error(503, ResponseBody.create(null, "{}"))
            words.removeAll { it.word == word }
            return Response.success<Unit>(204, null)
        }
    }

    private fun awaitUi(check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < end) {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(50)
        }
        instrumentation.runOnMainSync { assertTrue("Favorites UI did not reach expected state", check()) }
    }

    private fun withPage(check: (FavoritesActivity, FakeApi) -> Unit) {
        val oldApi = FavoritesActivity.apiService
        val oldAccount = RetrofitClient.account
        val oldUser = RetrofitClient.userId
        val oldToken = RetrofitClient.token
        val fake = FakeApi()
        instrumentation.runOnMainSync {
            FavoritesActivity.apiService = fake
            RetrofitClient.account = "__favorites_page_test__"
            RetrofitClient.userId = 2000000000
            RetrofitClient.token = "test"
        }
        var activity: FavoritesActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, FavoritesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as FavoritesActivity
            awaitUi { activity.findViewById<ListView>(R.id.favoritesWords).count == 2 }
            check(activity, fake)
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                FavoritesActivity.apiService = oldApi
                RetrofitClient.account = oldAccount
                RetrofitClient.userId = oldUser
                RetrofitClient.token = oldToken
            }
        }
    }

    @Test fun profileOpensFavoritesAndRowsHaveNoSelectionControls() = withPage { activity, _ ->
        instrumentation.runOnMainSync {
            fun noCheckbox(view: View) {
                assertFalse(view is CheckBox)
                if (view is ViewGroup) for (i in 0 until view.childCount) noCheckbox(view.getChildAt(i))
            }
            noCheckbox(activity.findViewById(R.id.favoritesRoot))
        }
        SystemClock.sleep(250)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(context.getExternalFilesDir(null), "favorites-page.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        val monitor = instrumentation.addMonitor(FavoritesActivity::class.java.name, null, false)
        var opened: FavoritesActivity? = null
        try {
            instrumentation.runOnMainSync {
                val profile = ProfileFragment()
                activity.supportFragmentManager.beginTransaction().add(android.R.id.content, profile).commitNow()
                profile.requireView().findViewById<View>(R.id.profileFavorites).performClick()
            }
            opened = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? FavoritesActivity
            assertNotNull("Profile favorite entry must open FavoritesActivity", opened)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { opened?.finish() }
        }
    }

    @Test fun paginationRefreshAndDeletionPreserveDataOnFailure() = withPage { activity, fake ->
        onView(withContentDescription("删除收藏 movie")).perform(click())
        onView(withText("取消")).perform(click())
        assertEquals(0, fake.deleteCalls)
        fake.failDelete = true
        onView(withContentDescription("删除收藏 movie")).perform(click())
        onView(withId(android.R.id.button1)).perform(click())
        awaitUi { activity.findViewById<TextView>(R.id.favoritesStatus).text.startsWith("删除失败") }
        assertEquals(3, fake.words.size)
        fake.failDelete = false
        onView(withContentDescription("删除收藏 movie")).perform(click())
        onView(withId(android.R.id.button1)).perform(click())
        awaitUi { activity.findViewById<ListView>(R.id.favoritesWords).count == 1 }
        onView(withId(R.id.favoritesMore)).perform(click())
        awaitUi { activity.findViewById<ListView>(R.id.favoritesWords).count == 2 }
        instrumentation.runOnMainSync { assertEquals(View.GONE, activity.findViewById<View>(R.id.favoritesMore).visibility) }
        fake.failRead = true
        onView(withId(R.id.favoritesRefresh)).perform(swipeDown())
        awaitUi { activity.findViewById<TextView>(R.id.favoritesStatus).text.startsWith("收藏加载失败") }
        instrumentation.runOnMainSync { assertEquals(2, activity.findViewById<ListView>(R.id.favoritesWords).count) }
        fake.failRead = false
        onView(withId(R.id.favoritesMore)).perform(click())
        awaitUi { activity.findViewById<TextView>(R.id.favoritesStatus).text.startsWith("共 2") }
        for (word in listOf("beautiful", "world")) {
            onView(withContentDescription("删除收藏 $word")).perform(click())
            onView(withId(android.R.id.button1)).perform(click())
            awaitUi { activity.findViewById<ListView>(R.id.favoritesWords).count == fake.words.size }
        }
        awaitUi { activity.findViewById<View>(R.id.favoritesEmpty).visibility == View.VISIBLE }
        val reads = fake.readCalls
        onView(withId(R.id.favoritesRefresh)).perform(swipeDown())
        awaitUi { fake.readCalls > reads }
        assertTrue(fake.words.isEmpty())
    }
}
