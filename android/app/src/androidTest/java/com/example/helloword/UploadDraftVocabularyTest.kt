package com.example.helloword

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadDraftVocabularyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val account = "__draft_vocabulary_test__"
    private val original = UploadSession("", "保留的电影.mp4", 1048576, "00:01:00",
        UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList(), 1))

    private fun withStore(check: (VideoUploadStore) -> Unit) {
        val oldAccount = RetrofitClient.account
        val oldUser = RetrofitClient.userId
        val oldToken = RetrofitClient.token
        lateinit var store: VideoUploadStore
        instrumentation.runOnMainSync {
            RetrofitClient.account = account
            RetrofitClient.userId = 2_000_000_000
            RetrofitClient.token = "test"
            store = VideoUploadStore.forAccount(context, account)
            store.clear()
            store.select(original)
        }
        try { check(store) } finally {
            instrumentation.runOnMainSync {
                store.forgetDeletedMovie(2_000_000_017)
                store.clear()
                RetrofitClient.account = oldAccount
                RetrofitClient.userId = oldUser
                RetrofitClient.token = oldToken
                oldAccount?.let { VideoUploadStore.forAccount(context, it) }
            }
        }
    }

    @Test fun reenteringUploadUpdatesBookAndSelectedIdsAndPersistsWithoutRemovingVideo() = withStore { store ->
        val selections = listOf(
            UploadVocabulary(UploadVocabulary.ALL_BOOK, emptyList(), 3),
            UploadVocabulary(UploadVocabulary.SELECTED_BOOK, listOf(12, 35), 4),
            UploadVocabulary(UploadVocabulary.SELECTED_BOOK, listOf(68), 4),
            UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList(), 1)
        )
        for (selection in selections) {
            val activity = instrumentation.startActivitySync(Intent(context, movie_upload::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(UploadVocabulary.EXTRA_BOOK_ID, selection.effectiveBookId)
                putExtra(UploadVocabulary.EXTRA_SOURCE,
                    if (selection.isSelected) UploadVocabulary.SOURCE_WORDS else UploadVocabulary.SOURCE_BOOK)
                if (selection.isSelected) putExtra(UploadVocabulary.EXTRA_WORD_IDS, selection.wordIds.toIntArray())
            }) as movie_upload
            try {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(original.copy(vocabulary = selection), store.state.value)
                    assertEquals(selection.summary, activity.findViewById<TextView>(R.id.uploadVocabularySummary).text.toString())
                    assertEquals(original.name, activity.findViewById<TextView>(R.id.movieName).text.toString())
                    val json = context.getSharedPreferences("video_upload_sessions", Context.MODE_PRIVATE)
                        .getString(account, null)
                    assertEquals(store.state.value, Gson().fromJson(json, UploadSession::class.java))
                }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
    }

    @Test fun recreationDoesNotReapplyOldIntentOverNewerDraft() = withStore { store ->
        val activity = instrumentation.startActivitySync(Intent(context, movie_upload::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UploadVocabulary.EXTRA_BOOK_ID, 3)
            putExtra(UploadVocabulary.EXTRA_SOURCE, UploadVocabulary.SOURCE_BOOK)
        }) as movie_upload
        val newer = UploadVocabulary(UploadVocabulary.SELECTED_BOOK, listOf(35), 4)
        val monitor = instrumentation.addMonitor(movie_upload::class.java.name, null, false)
        var recreated: movie_upload? = null
        try {
            instrumentation.runOnMainSync { store.updateDraftVocabulary(newer); activity.recreate() }
            recreated = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? movie_upload
            assertNotNull(recreated)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(newer, store.state.value!!.vocabulary)
                assertEquals(newer.summary, recreated!!.findViewById<TextView>(R.id.uploadVocabularySummary).text.toString())
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { recreated?.finish(); activity.finish() }
        }
    }

    @Test fun submittedUncertainAndOtherAccountDraftsKeepTheirOriginalRange() = withStore { store ->
        val replacement = UploadVocabulary(UploadVocabulary.ALL_BOOK, emptyList(), 3)
        instrumentation.runOnMainSync {
            for (phase in UploadPhase.entries.filter { it != UploadPhase.READY }) {
                val submitted = original.copy(phase = phase,
                    result = VideoData(original.name, "test.mp4", "test.mp4", 2_000_000_017, "pending"))
                store.select(submitted)
                store.updateDraftVocabulary(replacement)
                assertEquals(submitted, store.state.value)
                store.forgetDeletedMovie(2_000_000_017)
            }
            store.select(original)
            RetrofitClient.account = "__another_draft_account__"
            store.updateDraftVocabulary(replacement)
            assertEquals(original, store.state.value)
            RetrofitClient.account = account
        }
    }
}
