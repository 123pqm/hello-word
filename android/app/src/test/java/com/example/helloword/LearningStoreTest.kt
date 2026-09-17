package com.example.helloword

import com.example.helloword.model.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LearningStoreTest {
    private fun session(phase: UploadPhase = UploadPhase.READY, id: Int? = null) =
        UploadSession("content://video/1", "lesson.mp4", 1234, "00:01:00",
            UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList()), phase = phase,
            result = id?.let { VideoData("lesson.mp4", "stored.mp4", "videos/stored.mp4", it, "pending") })

    @Test fun selectedMetadataThenServerIdAndClearAreShared() {
        LearningStore.activateAccount("metadata", null)
        LearningStore.syncSession("metadata", session())
        assertEquals("lesson.mp4", LearningStore.recentMovie.value!!.name)
        assertEquals(1234L, LearningStore.recentMovie.value!!.size)
        assertNull(LearningStore.recentMovie.value!!.movieId)
        LearningStore.syncSession("metadata", session(UploadPhase.PROCESSING, 17))
        assertEquals(17, LearningStore.recentMovie.value!!.movieId)
        LearningStore.syncSession("metadata", null)
        assertNull(LearningStore.recentMovie.value)
    }

    @Test fun completionIsEmittedOnceAndNeverReplayedToNewSubscribers() = runBlocking {
        LearningStore.activateAccount("event", session(UploadPhase.PROCESSING, 17))
        val events = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            LearningStore.analysisCompleted.collect { events.add(it) }
        }
        val completed = session(UploadPhase.COMPLETED, 17)
        LearningStore.syncSession("event", completed)
        yield()
        LearningStore.syncSession("event", completed)
        LearningStore.activateAccount("event", completed)
        LearningStore.syncSession("event", completed.copy(flashcardsOpened = true))
        yield()
        assertEquals(listOf(17), events)
        assertTrue(LearningStore.analysisCompleted.replayCache.isEmpty())
        collector.cancel()
    }

    @Test fun backgroundResultFromAnotherAccountCannotOverwriteHome() {
        LearningStore.activateAccount("old", session())
        LearningStore.activateAccount("new", null)
        LearningStore.syncSession("old", session(UploadPhase.COMPLETED, 17))
        assertNull(LearningStore.recentMovie.value)
    }

    @Test fun multiplePagesCannotClaimSameNavigationConcurrently() {
        LearningStore.activateAccount("navigation", session(UploadPhase.COMPLETED, 17))
        assertTrue(LearningStore.claimNavigation("navigation", 17))
        assertFalse(LearningStore.claimNavigation("navigation", 17))
        assertFalse(LearningStore.claimNavigation("navigation", 18))
        assertFalse(LearningStore.claimNavigation("another", 17))
        LearningStore.releaseNavigation("navigation", 17)
        assertTrue(LearningStore.claimNavigation("navigation", 17))
        LearningStore.releaseNavigation("navigation", 17)
    }

    @Test fun completedHandledSessionRestoresMovieWithoutSendingEvent() = runBlocking {
        LearningStore.activateAccount("restored", null)
        val events = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            LearningStore.analysisCompleted.collect { events.add(it) }
        }
        LearningStore.syncSession("restored", session(UploadPhase.COMPLETED, 17).copy(flashcardsOpened = true))
        yield()
        assertTrue(events.isEmpty())
        assertEquals(17, LearningStore.recentMovie.value!!.movieId)
        collector.cancel()
    }
}
