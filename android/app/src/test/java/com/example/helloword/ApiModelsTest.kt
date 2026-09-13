package com.example.helloword

import com.example.helloword.model.Cet4Response
import com.example.helloword.model.LoginResponse
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ApiModelsTest {
    @Test fun loginReadsBackendAccessToken() {
        val result = Gson().fromJson(
            """{"account":"student","reply":"登录成功","access_token":"sample-jwt","token_type":"bearer","expires_in":1800}""",
            LoginResponse::class.java
        )
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
