package com.example.helloword

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** 从闪卡打开同一个 AI 页面，返回时保留原闪卡位置。 */
class AiAssistantActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_WORD = "ai_context_word"
        const val EXTRA_SENTENCE = "ai_context_sentence"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ai_assistant)
        val root = findViewById<View>(R.id.aiStandaloneContainer)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.aiStandaloneContainer, AiAssistantFragment().apply {
                    arguments = Bundle().apply {
                        putBoolean(AiAssistantFragment.ARG_SHOW_BACK, true)
                        putString(AiAssistantFragment.ARG_WORD, intent.getStringExtra(EXTRA_WORD))
                        putString(AiAssistantFragment.ARG_SENTENCE, intent.getStringExtra(EXTRA_SENTENCE))
                    }
                })
                .commit()
        }
    }
}
