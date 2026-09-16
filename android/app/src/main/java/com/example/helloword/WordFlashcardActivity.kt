package com.example.helloword

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.helloword.model.MovieWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class WordFlashcardActivity : AppCompatActivity() {

    private lateinit var tvWord: TextView
    private lateinit var tvMeaning: TextView
    private lateinit var btnKnown: Button
    private lateinit var btnUnknown: Button
    private lateinit var btnNext: Button
    private lateinit var btnPrevious: Button
    private lateinit var tvProgress: TextView
    private var words: List<MovieWord> = emptyList()
    private var currentIndex = 0
    private var loadFailed = false
    private var loading = false
    private var movieId = 0

    companion object {
        const val EXTRA_MOVIE_ID = "flashcard_movie_id"
        const val EXTRA_VIDEO_URI = "flashcard_video_uri"
        private const val STATE_INDEX = "flashcard_index"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 保留现有闪卡布局，内容由后端匹配结果填充。
        setContentView(R.layout.word_flashcard)

        // 找到 XML 里面的组件
        tvWord = findViewById(R.id.tvWord)
        tvMeaning = findViewById(R.id.tvMeaning)

        btnKnown = findViewById(R.id.btnKnown)
        btnUnknown = findViewById(R.id.btnUnknown)
        btnNext = findViewById(R.id.btnNext)
        btnPrevious = findViewById(R.id.btnPrevious)
        tvProgress = findViewById(R.id.tvFlashcardProgress)
        movieId = intent.getIntExtra(EXTRA_MOVIE_ID, 0)
        currentIndex = savedInstanceState?.getInt(STATE_INDEX) ?: 0
        // 后端没有返回音标、词性或例句，不显示原来的示例内容。
        listOf(R.id.tvPhonetic, R.id.tvPos, R.id.tvSentence, R.id.tvSentenceMeaning).forEach {
            findViewById<View>(it).visibility = View.GONE
        }

        // 点击“认识”
        btnKnown.setOnClickListener {
            println("这个单词认识")
        }

        // 点击“不认识”
        btnUnknown.setOnClickListener {
            println("加入生词本")
        }

        btnPrevious.setOnClickListener {
            if (!loading && !loadFailed && currentIndex > 0 && words.isNotEmpty()) {
                currentIndex--
                renderWord()
            }
        }

        // 点击下一个
        btnNext.setOnClickListener {
            when {
                loading -> Unit
                loadFailed -> loadWords()
                words.isEmpty() || currentIndex >= words.lastIndex -> finish()
                else -> { currentIndex++; renderWord() }
            }
        }
        loadWords()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_INDEX, currentIndex)
        super.onSaveInstanceState(outState)
    }

    private fun loadWords() {
        btnPrevious.isEnabled = false
        if (movieId <= 0) {
            tvWord.text = "缺少视频信息"
            tvMeaning.text = "请从上传页面打开闪卡"
            tvProgress.text = ""
            btnKnown.isEnabled = false
            btnUnknown.isEnabled = false
            btnNext.text = "返回"
            return
        }
        loading = true
        loadFailed = false
        tvWord.text = "正在加载…"
        tvMeaning.text = ""
        tvProgress.text = ""
        btnKnown.isEnabled = false
        btnUnknown.isEnabled = false
        btnNext.isEnabled = false
        lifecycleScope.launch {
            try {
                words = MovieWordsRepository.load(applicationContext, movieId).words
                currentIndex = currentIndex.coerceIn(0, words.lastIndex.coerceAtLeast(0))
                renderWord()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                loadFailed = true
                tvWord.text = "加载失败"
                tvMeaning.text = error.localizedMessage ?: "请检查网络后重试"
                btnNext.text = "重新加载"
            } finally {
                loading = false
                btnNext.isEnabled = true
            }
        }
    }

    private fun renderWord() {
        val word = words.getOrNull(currentIndex)
        btnPrevious.isEnabled = word != null && currentIndex > 0
        btnKnown.isEnabled = word != null
        btnUnknown.isEnabled = word != null
        if (word == null) {
            tvWord.text = "暂无匹配单词"
            tvMeaning.text = "视频处理已完成，但没有匹配到所选范围内的单词"
            tvProgress.text = "0 个单词"
            btnNext.text = "返回"
            findViewById<TextView>(R.id.playerPlaceholderText).text = "视频播放器区域"
            return
        }
        tvWord.text = word.word
        tvMeaning.text = word.meaning
        tvProgress.text = "${currentIndex + 1} / ${words.size}"
        findViewById<TextView>(R.id.playerPlaceholderText).text = String.format(
            Locale.getDefault(), "视频播放器区域\n单词出现时间：%.2f～%.2f 秒", word.startTime, word.endTime)
        btnNext.text = if (currentIndex == words.lastIndex) "完成学习" else "下一个单词 →"
    }
}
