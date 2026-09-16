package com.example.helloword

import com.example.helloword.model.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class UploadSessionTest {
    private val draft = UploadSession("content://videos/123", "movie.mp4", 1024, "00:01:00",
        UploadVocabulary(UploadVocabulary.SELECTED_CET4, listOf(1, 91)))

    @Test fun selectedVideoAndVocabularySurviveDiskRoundTrip() {
        val restored = Gson().fromJson(Gson().toJson(draft), UploadSession::class.java).afterProcessRestart()
        assertEquals(draft, restored)
        assertTrue(restored.canUpload)
        assertTrue(restored.showConfirm)
    }

    @Test fun returningToLiveUploadKeepsButtonHiddenAndBlocksDuplicateSubmission() {
        val running = draft.copy(phase = UploadPhase.UPLOADING)
        assertFalse(running.showConfirm)
        assertFalse(running.canUpload)
    }

    @Test fun processDeathDoesNotLeavePermanentlyHiddenButton() {
        val running = draft.copy(phase = UploadPhase.UPLOADING)
        val restored = Gson().fromJson(Gson().toJson(running), UploadSession::class.java).afterProcessRestart()
        assertEquals(UploadPhase.INTERRUPTED, restored.phase)
        assertEquals(draft.uri, restored.uri)
        assertEquals(draft.vocabulary, restored.vocabulary)
        assertTrue(restored.showConfirm)
        assertTrue(restored.canUpload)
        assertTrue(restored.message.contains("重复上传"))
    }

    @Test fun successfulUploadRetainsServerResultAndCannotUploadAgain() {
        val complete = draft.copy(phase = UploadPhase.SUCCEEDED,
            result = VideoData("movie.mp4", "server.mp4", "uploads/videos/server.mp4"))
        val restored = Gson().fromJson(Gson().toJson(complete), UploadSession::class.java).afterProcessRestart()
        assertEquals(complete, restored)
        assertTrue(restored.showConfirm)
        assertFalse(restored.canUpload)
    }

    @Test fun failureRetainsSelectionAndAllowsRetry() {
        val failed = draft.copy(phase = UploadPhase.FAILED, message = "网络断开")
        assertEquals(failed, failed.afterProcessRestart())
        assertTrue(failed.canUpload)
        assertTrue(failed.showConfirm)
    }

    private val processing = draft.copy(phase = UploadPhase.PROCESSING,
        result = VideoData("movie.mp4", "server.mp4", "uploads/server.mp4", 17, "pending"))

    @Test fun pendingSurvivesProcessRestartAndKeepsSameTaskWithoutReuploading() {
        val restored = Gson().fromJson(Gson().toJson(processing), UploadSession::class.java).afterProcessRestart()
        assertEquals(17, restored.result?.movieId)
        assertEquals(UploadPhase.PROCESSING, restored.phase)
        assertFalse(restored.showConfirm)
        assertFalse(restored.canUpload)
    }

    @Test fun pendingThenCompletedWithZeroWordsStillCompletes() {
        val pending = processing.withServerStatus(MovieStatus(17, "pending", null, 0))
        assertFalse(pending.showConfirm)
        val completed = pending.withServerStatus(MovieStatus(17, "completed", null, 0))
        assertEquals(UploadPhase.COMPLETED, completed.phase)
        assertTrue(completed.showConfirm)
        assertFalse(completed.canUpload)
        assertTrue(completed.message.contains("0"))
    }

    @Test fun serverFailureStopsProcessingAndAllowsExplicitRetry() {
        val failed = processing.withServerStatus(MovieStatus(17, "failed", "识别失败", 0))
        assertEquals(UploadPhase.PROCESS_FAILED, failed.phase)
        assertTrue(failed.showConfirm)
        assertTrue(failed.canUpload)
        assertEquals("识别失败", failed.message)
    }

    @Test(expected = IllegalArgumentException::class)
    fun responseForOtherMovieCannotCompleteCurrentMovie() {
        processing.withServerStatus(MovieStatus(18, "completed", null, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownStatusCannotBeTreatedAsCompleted() {
        processing.withServerStatus(MovieStatus(17, "unknown", null, 0))
    }

    @Test fun uploadResponseReadsBackendSnakeCaseMovieId() {
        val response = Gson().fromJson(
            """{"code":200,"data":{"movie_id":17,"status":"pending","originalFilename":"movie.mp4","filename":"server.mp4","path":"uploads/server.mp4"}}""",
            VideoUploadResponse::class.java)
        assertEquals(17, response.data?.movieId)
        assertEquals("pending", response.data?.status)
    }
}
