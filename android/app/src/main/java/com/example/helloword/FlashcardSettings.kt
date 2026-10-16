package com.example.helloword

import android.content.Context
import com.example.helloword.api.RetrofitClient

data class FlashcardDisplayDefaults(val showMeaning: Boolean = false, val showSentence: Boolean = true)

/** 按账号保存初始化设置；闪卡上的临时揭示不写回开关。 */
class FlashcardSettings(context: Context, account: String? = RetrofitClient.account) {
    private val preferences = context.applicationContext.getSharedPreferences("flashcard_settings", Context.MODE_PRIVATE)
    private val prefix = "${account ?: "__guest__"}:"

    fun read() = FlashcardDisplayDefaults(
        preferences.getBoolean(prefix + "show_meaning", false),
        preferences.getBoolean(prefix + "show_sentence", true)
    )

    fun setShowMeaning(value: Boolean) {
        preferences.edit().putBoolean(prefix + "show_meaning", value).apply()
    }

    fun setShowSentence(value: Boolean) {
        preferences.edit().putBoolean(prefix + "show_sentence", value).apply()
    }
}
