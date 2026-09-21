package com.example.helloword

import com.example.helloword.model.Cet4Response
import com.example.helloword.model.LoginResponse
import com.example.helloword.model.MovieWordsResponse
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ApiModelsTest {
    @Test fun checkinReadsServerDatesAndStreak() {
        val json = """{"code":200,"data":{"user_id":7,"today":"2026-09-23","week_count":2,"streak_days":9,"next_checkin_after_seconds":43200,"days":[{"date":"2026-09-23","weekday":3,"checked":true,"is_today":true}]}}"""
        val data = Gson().fromJson(json, com.example.helloword.model.CheckinResponse::class.java).data!!
        assertEquals(7, data.userId)
        assertEquals(2, data.weekCount)
        assertEquals(9, data.streakDays)
        assertEquals(43200L, data.nextCheckinAfterSeconds)
        assertEquals("2026-09-23", data.days[0].date)
        assertTrue(data.days[0].isToday)
        assertTrue(data.days[0].checked)
    }

    @Test fun movieSentencesReadAndSurviveCacheRoundTripWithLegacyRows() {
        val gson = Gson()
        val result = gson.fromJson(
            """{"code":200,"data":{"movie_id":17,"words":[
              {"word":"beautiful","meaning":"美丽的","start_time":1,"end_time":2,"sentence_text":"It's a beautiful day."},
              {"word":"old","meaning":"旧的","start_time":3,"end_time":4},
              {"word":"legacy","meaning":"遗留的","start_time":5,"end_time":6,"sentence_text":null}
            ]}}""",
            MovieWordsResponse::class.java
        )
        assertEquals("It's a beautiful day.", result.data!!.words[0].sentenceText)
        assertNull(result.data.words[1].sentenceText)
        assertNull(result.data.words[2].sentenceText)
        assertEquals(result, gson.fromJson(gson.toJson(result), MovieWordsResponse::class.java))
    }

    @Test fun loginReadsBackendAccessToken() {
        val result = Gson().fromJson(
            """{"user_id":7,"account":"student","reply":"登录成功","access_token":"sample-jwt","token_type":"bearer","expires_in":1800}""",
            LoginResponse::class.java
        )
        assertEquals(7, result.userId)
        assertEquals("sample-jwt", result.accessToken)
        assertEquals("登录成功", result.reply)
        assertEquals(1800, result.expiresIn)
    }

    @Test fun cet4ReadsWordsAndNullablePartOfSpeech() {
        val result = Gson().fromJson(
            """{"data":[{"id":1,"word":"Africa","meaning":"非洲","pos":"n."},{"id":2,"word":"test","meaning":"测试","pos":null}]}""",
            Cet4Response::class.java
        )
        assertEquals(2, result.data!!.size)
        assertEquals("非洲", result.data[0].meaning)
        assertEquals("n.", result.data[0].pos)
        assertNull(result.data[1].pos)
    }
}
