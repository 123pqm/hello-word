package com.example.helloword

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.LoginResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthSessionTest {
    @Test fun sessionRestoresSwitchesAndExpires() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        try {
            RetrofitClient.saveSession(LoginResponse("test_a", "ok", "token-a", "bearer", 1800, 71))
            RetrofitClient.token = null
            RetrofitClient.account = null
            RetrofitClient.userId = null
            RetrofitClient.initialize(context)
            assertEquals("token-a", RetrofitClient.token)
            assertEquals("test_a", RetrofitClient.account)
            assertEquals(71, RetrofitClient.userId)
            assertTrue(RetrofitClient.hasValidSession())
            assertFalse(prefs.contains("password"))

            RetrofitClient.saveSession(LoginResponse("test_b", "ok", "token-b", "bearer", 1800, 72))
            RetrofitClient.initialize(context)
            assertEquals("token-b", RetrofitClient.token)
            assertEquals(72, RetrofitClient.userId)

            // 已被拒绝的内存凭证不能因磁盘上仍有旧值而跳过登录页。
            RetrofitClient.token = null
            assertFalse(RetrofitClient.hasValidSession())

            prefs.edit().putLong("expires_at", System.currentTimeMillis() - 1).commit()
            RetrofitClient.initialize(context)
            assertNull(RetrofitClient.token)
            assertNull(RetrofitClient.userId)
            assertFalse(RetrofitClient.hasValidSession())
            assertNull(prefs.getString("access_token", null))
        } finally {
            val editor = prefs.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.commit()
            RetrofitClient.initialize(context)
        }
    }
}
