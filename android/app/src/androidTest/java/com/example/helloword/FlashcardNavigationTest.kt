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

    // Opt-in smoke check against an existing movie; never changes its account or data.
    @Test fun existingMovieRendersAndPlays() {
        val movieId = InstrumentationRegistry.getArguments().getString("recoveryMovieId")?.toIntOrNull()
        org.junit.Assume.assumeTrue(movieId != null)
        val activity = instrumentation.startActivitySync(Intent(context, WordFlashcardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(WordFlashcardActivity.EXTRA_MOVIE_ID, movieId!!)
        }) as WordFlashcardActivity
        try {
            var ready = false
            val deadline = SystemClock.uptimeMillis() + 30000
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    ready = activity.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView).player?.playbackState ==
                        androidx.media3.common.Player.STATE_READY &&
                        activity.findViewById<TextView>(R.id.tvSentence).text.isNotBlank()
                }
                if (!ready) SystemClock.sleep(100)
            }
            assertTrue("Movie must load a real sentence and prepare video", ready)
            instrumentation.runOnMainSync {
                val sentence = activity.findViewById<TextView>(R.id.tvSentence)
                assertTrue(sentence.height > 0)
                val area = activity.findViewById<android.view.View>(R.id.wordCardRevealArea)
                assertTrue(area.height < 200 * activity.resources.displayMetrics.density)
                if (!FlashcardSettings(context).read().showSentence) {
                    activity.findViewById<android.view.View>(R.id.sentenceRevealArea).performClick()
                }
                sentence.requestRectangleOnScreen(android.graphics.Rect(0, 0, sentence.width, sentence.height), true)
                activity.findViewById<android.view.View>(R.id.btnJumpToSentence).performClick()
            }
            SystemClock.sleep(1500)
            instrumentation.runOnMainSync {
                val sentence = activity.findViewById<TextView>(R.id.tvSentence)
                assertTrue("Sentence is visible on screen", sentence.getGlobalVisibleRect(android.graphics.Rect()))
                assertEquals(1f, activity.findViewById<android.view.View>(R.id.sentenceContent).alpha)
                val player = activity.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView).player!!
                assertNull(player.playerError)
                assertTrue("Jump starts playback", player.playWhenReady)
                player.pause()
            }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null), "flashcard-recovery-$movieId.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

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

    private fun awaitFavorite(activity: Activity, saved: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync {
                val button = activity.findViewById<android.widget.ImageButton>(R.id.btnFavoriteWord)
                ready = button.isEnabled && button.isSelected == saved
            }
            if (ready) return
            SystemClock.sleep(50)
        }
        instrumentation.runOnMainSync {
            val button = activity.findViewById<android.widget.ImageButton>(R.id.btnFavoriteWord)
            assertTrue(button.isEnabled)
            assertEquals(saved, button.isSelected)
        }
    }

    private fun withCompletedMovie(words: List<MovieWord>, defaults: FlashcardDisplayDefaults = FlashcardDisplayDefaults(),
                                   favoriteApi: com.example.helloword.api.ApiService = TestFavoritesApi(),
                                   check: (movie_upload, WordFlashcardActivity) -> Unit) {
        val movieId = 2_000_000_001
        val account = "__flashcard_test__"
        val settings = FlashcardSettings(context, account)
        val savedSettings = settings.read()
        settings.setShowMeaning(defaults.showMeaning)
        settings.setShowSentence(defaults.showSentence)
        val previousAccount = RetrofitClient.account
        val previousToken = RetrofitClient.token
        val savedUserId = RetrofitClient.userId
        val savedApi = MovieWordsRepository.apiService
        val savedFavoritesApi = WordFlashcardActivity.favoritesApi
        WordFlashcardActivity.favoritesApi = favoriteApi
        MovieWordsRepository.apiService = movieApi(MovieWords(movieId, words))
        lateinit var store: VideoUploadStore
        instrumentation.runOnMainSync {
            RetrofitClient.account = account
            RetrofitClient.token = "test"
            RetrofitClient.userId = 2_000_000_000
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
                RetrofitClient.userId = savedUserId
                MovieWordsRepository.apiService = savedApi
                WordFlashcardActivity.favoritesApi = savedFavoritesApi
                settings.setShowMeaning(savedSettings.showMeaning)
                settings.setShowSentence(savedSettings.showSentence)
            }
        }
    }

    @Test fun completedUploadOpensRealWordsAndNextChangesCard() {
        withCompletedMovie(listOf(MovieWord("movie", "电影", 1.25, 1.8, "It's a movie."), MovieWord("world", "世界", 7.0, 7.5))) { _, flashcard ->
            awaitText(flashcard, R.id.tvWord, "movie")
            awaitText(flashcard, R.id.tvMeaning, "电影")
            awaitText(flashcard, R.id.tvFlashcardProgress, "1 / 2")
            awaitText(flashcard, R.id.tvSentence, "It's a movie.")
            instrumentation.runOnMainSync {
                val mask = flashcard.findViewById<android.view.View>(R.id.wordCardMask)
                val reveal = flashcard.findViewById<android.view.View>(R.id.wordCardRevealArea)
                assertEquals(reveal, mask.parent)
                assertEquals(android.view.View.GONE, flashcard.findViewById<android.view.View>(R.id.tvSentenceStatus).visibility)
                assertEquals(android.view.View.VISIBLE, mask.visibility)
                reveal.performClick()
                assertEquals(android.view.View.GONE, mask.visibility)
            }
            instrumentation.runOnMainSync { flashcard.findViewById<TextView>(R.id.btnNext).performClick() }
            awaitText(flashcard, R.id.tvWord, "world")
            awaitText(flashcard, R.id.tvMeaning, "世界")
            awaitText(flashcard, R.id.btnNext, "完成学习")
            instrumentation.runOnMainSync {
                assertEquals(android.view.View.GONE, flashcard.findViewById<TextView>(R.id.tvSentence).visibility)
                assertEquals("", flashcard.findViewById<TextView>(R.id.tvSentence).text.toString())
                assertEquals(android.view.View.VISIBLE, flashcard.findViewById<android.view.View>(R.id.tvSentenceStatus).visibility)
                assertEquals(android.view.View.VISIBLE, flashcard.findViewById<android.view.View>(R.id.wordCardMask).visibility)
                flashcard.findViewById<TextView>(R.id.btnPrevious).performClick()
            }
            awaitText(flashcard, R.id.tvSentence, "It's a movie.")
        }
    }

    @Test fun longContentScrollsToNavigationWithoutOverlappingComponents() {
        withCompletedMovie(listOf(MovieWord("extraordinary", "非常特别的；非凡的；".repeat(25),
            1.0, 2.0, "This is an extraordinary example with a long explanation. ".repeat(12))),
            FlashcardDisplayDefaults(true, true)) { _, card ->
            awaitText(card, R.id.tvWord, "extraordinary")
            instrumentation.runOnMainSync {
                card.findViewById<TextView>(R.id.tvMeaning).textSize = 28f
                card.findViewById<TextView>(R.id.tvSentence).textSize = 26f
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
            instrumentation.runOnMainSync {
                fun bounds(id: Int): android.graphics.Rect {
                    val view = card.findViewById<android.view.View>(id)
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    return android.graphics.Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                }
                val ordered = listOf(R.id.moviePlayer, R.id.videoSourceStatus, R.id.chooseLocalMovie,
                    R.id.btnFavoriteWord, R.id.tvWord, R.id.wordCardRevealArea, R.id.sentenceDivider,
                    R.id.sentenceRevealArea, R.id.btnJumpToSentence, R.id.btnNext)
                ordered.zipWithNext().forEach { (before, after) ->
                    assertTrue("Components must not overlap: $before / $after", bounds(before).bottom <= bounds(after).top)
                }
                assertEquals(android.view.View.GONE, card.findViewById<android.view.View>(R.id.wordCardMask).visibility)
                assertEquals(android.view.View.GONE, card.findViewById<android.view.View>(R.id.sentenceMask).visibility)
                val scroll = card.findViewById<android.widget.ScrollView>(R.id.flashcardScroll)
                assertTrue(scroll.isVerticalScrollBarEnabled)
                assertTrue("Long content must be scrollable", scroll.canScrollVertically(1))
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                val viewport = bounds(R.id.flashcardScroll)
                val next = bounds(R.id.btnNext)
                assertTrue("Bottom navigation is reachable", next.top >= viewport.top && next.bottom <= viewport.bottom)
            }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null), "flashcard-scroll-long-content.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }

    @Test fun emptyCompletedResultOpensEmptyStateWithoutSampleWords() {
        withCompletedMovie(emptyList()) { _, flashcard ->
            awaitText(flashcard, R.id.tvWord, "暂无匹配单词")
            awaitText(flashcard, R.id.btnNext, "返回")
            instrumentation.runOnMainSync { assertFalse(flashcard.findViewById<android.view.View>(R.id.wordCardRevealArea).isEnabled) }
        }
    }

    private class TestFavoritesApi : com.example.helloword.api.ApiService by RetrofitClient.apiService {
        val saved = mutableSetOf<String>()
        var failSave = false
        var saveGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun favoriteStatus(word: String, authorization: String) =
            retrofit2.Response.success(FavoriteStatusResponse(200, FavoriteStatus(word, word in saved)))
        override suspend fun saveFavorite(body: FavoriteRequest, authorization: String): retrofit2.Response<FavoriteResponse> {
            saveGate?.await()
            if (failSave) return retrofit2.Response.error(503, okhttp3.ResponseBody.create(null, "{}"))
            saved.add(body.word)
            return retrofit2.Response.success(FavoriteResponse(200, FavoriteWord(1, body.word, "电影", "It's a movie.",
                body.movieId, "test.mp4", 1.0, 2.0, "2026-09-23T00:00:00")))
        }
        override suspend fun deleteFavorite(word: String, authorization: String): retrofit2.Response<Unit> {
            saved.remove(word)
            return retrofit2.Response.success<Unit>(204, null)
        }
    }

    @Test fun favoritePersistsAcrossCardsAndReopenAndFailureDoesNotPretendSuccess() {
        val api = TestFavoritesApi()
        val cards = listOf(MovieWord("movie", "电影", 1.0, 2.0, "It's a movie."), MovieWord("world", "世界", 3.0, 4.0))
        withCompletedMovie(cards, favoriteApi = api) { _, card ->
            awaitFavorite(card, false)
            instrumentation.runOnMainSync { card.findViewById<android.view.View>(R.id.btnFavoriteWord).performClick() }
            awaitFavorite(card, true)
            instrumentation.runOnMainSync {
                val button = card.findViewById<android.view.View>(R.id.btnFavoriteWord)
                button.requestRectangleOnScreen(android.graphics.Rect(0, 0, button.width, button.height), true)
            }
            SystemClock.sleep(200)
            instrumentation.runOnMainSync {
                val title = card.findViewById<TextView>(R.id.tvWord)
                val area = card.findViewById<android.view.View>(R.id.flashcardCard)
                val star = card.findViewById<android.widget.ImageButton>(R.id.btnFavoriteWord)
                val titlePos = IntArray(2); val areaPos = IntArray(2); val starPos = IntArray(2)
                title.getLocationOnScreen(titlePos); area.getLocationOnScreen(areaPos); star.getLocationOnScreen(starPos)
                assertEquals("Word stays centered independently of star", areaPos[0] + area.width / 2f,
                    titlePos[0] + title.width / 2f, 2f)
                assertTrue("Star is above the word", starPos[1] < titlePos[1])
                assertTrue("Star is at right corner", starPos[0] > areaPos[0] + area.width / 2)
                assertTrue("Icon stays small", star.width - star.paddingLeft - star.paddingRight <= 24 * card.resources.displayMetrics.density)
            }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null), "flashcard-favorite.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { card.findViewById<android.view.View>(R.id.btnNext).performClick() }
            awaitFavorite(card, false)
            instrumentation.runOnMainSync { card.findViewById<android.view.View>(R.id.btnPrevious).performClick() }
            awaitFavorite(card, true)
        }
        withCompletedMovie(cards, favoriteApi = api) { _, card ->
            awaitFavorite(card, true)
            instrumentation.runOnMainSync { card.findViewById<android.view.View>(R.id.btnFavoriteWord).performClick() }
            awaitFavorite(card, false)
            api.failSave = true
            instrumentation.runOnMainSync { card.findViewById<android.view.View>(R.id.btnFavoriteWord).performClick() }
            awaitFavorite(card, false)
            assertTrue(api.saved.isEmpty())
        }
    }

    @Test fun savingPreviousCardCannotMarkNextCardFavorite() {
        val api = TestFavoritesApi()
        api.saveGate = kotlinx.coroutines.CompletableDeferred()
        withCompletedMovie(listOf(MovieWord("movie", "电影", 1.0, 2.0), MovieWord("world", "世界", 3.0, 4.0)),
            favoriteApi = api) { _, card ->
            awaitFavorite(card, false)
            instrumentation.runOnMainSync {
                card.findViewById<android.view.View>(R.id.btnFavoriteWord).performClick()
                assertFalse(card.findViewById<android.view.View>(R.id.btnFavoriteWord).isEnabled)
                card.findViewById<android.view.View>(R.id.btnNext).performClick()
            }
            awaitFavorite(card, false)
            instrumentation.runOnMainSync { api.saveGate!!.complete(Unit) }
            instrumentation.waitForIdleSync()
            awaitFavorite(card, false)
            instrumentation.runOnMainSync { card.findViewById<android.view.View>(R.id.btnPrevious).performClick() }
            awaitFavorite(card, true)
            assertEquals(setOf("movie"), api.saved)
        }
    }

    @Test fun bothDefaultsAreIndependentAndReappliedOnEveryCard() {
        for (meaning in listOf(false, true)) for (sentence in listOf(false, true)) {
            val defaults = FlashcardDisplayDefaults(meaning, sentence)
            withCompletedMovie(listOf(
                MovieWord("movie", "电影", 1.0, 2.0, "It's a movie."),
                MovieWord("world", "世界", 3.0, 4.0, "Hello world.")
            ), defaults) { _, flashcard ->
                awaitText(flashcard, R.id.tvWord, "movie")
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
                instrumentation.runOnMainSync {
                    fun expected(shown: Boolean) = if (shown) android.view.View.GONE else android.view.View.VISIBLE
                    val meaningMask = flashcard.findViewById<android.view.View>(R.id.wordCardMask)
                    val sentenceMask = flashcard.findViewById<android.view.View>(R.id.sentenceMask)
                    assertEquals(expected(meaning), meaningMask.visibility)
                    assertEquals(expected(sentence), sentenceMask.visibility)
                    val meaningArea = flashcard.findViewById<android.view.View>(R.id.wordCardRevealArea)
                    val sentenceArea = flashcard.findViewById<android.view.View>(R.id.sentenceRevealArea)
                    val density = flashcard.resources.displayMetrics.density
                    assertTrue("Meaning overlay must not stretch the card", meaningArea.height in 1..(160 * density).toInt())
                    assertTrue("Sentence must have a compact measured area", sentenceArea.height in 1..(160 * density).toInt())
                    assertEquals(if (sentence) 1f else 0f,
                        flashcard.findViewById<android.view.View>(R.id.sentenceContent).alpha)
                    flashcard.findViewById<android.view.View>(R.id.wordCardRevealArea).performClick()
                    assertEquals(expected(!meaning), meaningMask.visibility)
                    assertEquals(expected(sentence), sentenceMask.visibility)
                    flashcard.findViewById<android.view.View>(R.id.sentenceRevealArea).performClick()
                    assertEquals(expected(!sentence), sentenceMask.visibility)
                    assertEquals(defaults, FlashcardSettings(context, "__flashcard_test__").read())
                    flashcard.findViewById<android.view.View>(R.id.btnNext).performClick()
                    assertEquals(expected(meaning), meaningMask.visibility)
                    assertEquals(expected(sentence), sentenceMask.visibility)
                    flashcard.findViewById<android.view.View>(R.id.btnPrevious).performClick()
                    assertEquals(expected(meaning), meaningMask.visibility)
                    assertEquals(expected(sentence), sentenceMask.visibility)
                }
            }
        }
    }

    @Test fun movieFilesSurviveReplacingUploadAndAreAccountScoped() {
        val account = "__movie_files_test__"
        val prefs = context.getSharedPreferences("local_movie_files", android.content.Context.MODE_PRIVATE)
        val first = android.net.Uri.parse("content://test/videos/first")
        val second = android.net.Uri.parse("content://test/videos/second")
        instrumentation.runOnMainSync {
            val store = VideoUploadStore.forAccount(context, account)
            try {
                for ((id, uri) in listOf(2000000001 to first, 2000000002 to second)) {
                    store.select(UploadSession(uri.toString(), "test.mp4", 100, "00:01:00",
                        UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList()), phase = UploadPhase.COMPLETED,
                        result = VideoData("test.mp4", "test.mp4", "test.mp4", id, "completed")))
                }
                store.clear()
                assertEquals(first, LocalMovieFiles(context, account).find(2000000001))
                assertEquals(second, LocalMovieFiles(context, account).find(2000000002))
                assertNull(LocalMovieFiles(context, "__other_movie_files_test__").find(2000000001))
                LocalMovieFiles(context, account).remember(2000000002, first)
                store.select(UploadSession(second.toString(), "test.mp4", 100, "00:01:00",
                    UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList()), phase = UploadPhase.COMPLETED,
                    result = VideoData("test.mp4", "test.mp4", "test.mp4", 2000000002, "completed")))
                store.markFlashcardsOpened(2000000002)
                assertEquals("Saving the old upload must not replace a manual rebind", first,
                    LocalMovieFiles(context, account).find(2000000002))
            } finally {
                store.clear()
                prefs.edit().remove("$account:2000000001").remove("$account:2000000002").commit()
            }
        }
    }

    @Test fun profileSwitchesPersistAndNextCardUsesNewValues() {
        withCompletedMovie(listOf(MovieWord("movie", "电影", 1.0, 2.0, "It's a movie."),
            MovieWord("world", "世界", 3.0, 4.0, "Hello world."))) { _, flashcard ->
            awaitText(flashcard, R.id.tvWord, "movie")
            instrumentation.runOnMainSync {
                val manager = flashcard.supportFragmentManager
                val profile = ProfileFragment()
                manager.beginTransaction().add(android.R.id.content, profile).commitNow()
                profile.requireView().findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.profileShowMeaning).isChecked = true
                profile.requireView().findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.profileShowSentence).isChecked = false
                assertEquals(FlashcardDisplayDefaults(true, false), FlashcardSettings(context).read())
                manager.beginTransaction().remove(profile).commitNow()
                val reopened = ProfileFragment()
                manager.beginTransaction().add(android.R.id.content, reopened).commitNow()
                assertTrue(reopened.requireView().findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.profileShowMeaning).isChecked)
                assertFalse(reopened.requireView().findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.profileShowSentence).isChecked)
                manager.beginTransaction().remove(reopened).commitNow()
                flashcard.findViewById<android.view.View>(R.id.btnNext).performClick()
                assertEquals(android.view.View.GONE, flashcard.findViewById<android.view.View>(R.id.wordCardMask).visibility)
                assertEquals(android.view.View.VISIBLE, flashcard.findViewById<android.view.View>(R.id.sentenceMask).visibility)
            }
        }
    }
}
