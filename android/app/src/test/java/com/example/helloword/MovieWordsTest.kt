package com.example.helloword

import com.example.helloword.model.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class MovieWordsTest {
    @Test fun backendWordsAndTimestampsSurviveDiskTransferToFlashcards() {
        val response = Gson().fromJson("""{"code":200,"data":{"movie_id":17,"words":[
            {"word":"movie","meaning":"电影","start_time":1.25,"end_time":1.8},
            {"word":"world","meaning":"世界","start_time":7.0,"end_time":7.5}
        ]}}""", MovieWordsResponse::class.java)
        val cached = Gson().fromJson(Gson().toJson(response.data), MovieWords::class.java)
        assertEquals(17, cached.movieId)
        assertEquals(listOf("movie", "world"), cached.words.map { it.word })
        assertEquals("电影", cached.words[0].meaning)
        assertEquals(1.25, cached.words[0].startTime, 0.001)
        assertEquals(7.5, cached.words[1].endTime, 0.001)
    }

    @Test fun emptyMatchingResultIsValid() {
        val response = Gson().fromJson("""{"code":200,"data":{"movie_id":17,"words":[]}}""",
            MovieWordsResponse::class.java)
        assertTrue(response.data!!.words.isEmpty())
    }

    @Test fun returningFromFlashcardsDoesNotAutoOpenAgainAfterProcessRestart() {
        val session = UploadSession("content://video/1", "movie.mp4", 100, "00:01:00",
            UploadVocabulary(UploadVocabulary.ALL_CET4, emptyList()), phase = UploadPhase.COMPLETED,
            result = VideoData("movie.mp4", "server.mp4", "uploads/server.mp4", 17, "pending"),
            flashcardsOpened = true)
        val restored = Gson().fromJson(Gson().toJson(session), UploadSession::class.java).afterProcessRestart()
        assertTrue(restored.flashcardsOpened)
        assertEquals(UploadPhase.COMPLETED, restored.phase)
        assertEquals(17, restored.result?.movieId)
    }
}
