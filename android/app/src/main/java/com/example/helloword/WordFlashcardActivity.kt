package com.example.helloword

import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.helloword.component.MoviePlayerView
import com.example.helloword.model.MovieWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class WordFlashcardActivity : AppCompatActivity() {

    private lateinit var moviePlayer: MoviePlayerView
    private lateinit var wordCardContent: View
    private lateinit var wordCardMask: View
    private lateinit var wordCardRevealArea: View
    private var isWordRevealed = false
    private var hasWordContent = false
    private lateinit var tvWord: TextView
    private lateinit var tvMeaning: TextView
    private lateinit var tvSentence: TextView
    private lateinit var tvSentenceStatus: TextView
    private lateinit var sentenceDivider: View
    private lateinit var btnJumpToSentence: Button
    private lateinit var btnNext: Button
    private lateinit var btnPrevious: Button
    private lateinit var tvProgress: TextView
    private var words: List<MovieWord> = emptyList()
    private var currentIndex = 0
    private var loadFailed = false
    private var loading = false
    private var movieId = 0
    private var hasVideo = false

    companion object {
        const val EXTRA_MOVIE_ID = "flashcard_movie_id"
        const val EXTRA_VIDEO_URI = "flashcard_video_uri"
        private const val STATE_INDEX = "flashcard_index"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.word_flashcard)
        val root = findViewById<View>(R.id.flashcardRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        // 找到 XML 里面的组件
        tvWord = findViewById(R.id.tvWord)
        tvMeaning = findViewById(R.id.tvMeaning)
        tvSentence = findViewById(R.id.tvSentence)
        tvSentence.text = ""
        tvSentence.visibility = View.GONE
        tvSentenceStatus = findViewById(R.id.tvSentenceStatus)
        sentenceDivider = findViewById(R.id.sentenceDivider)
        sentenceDivider.visibility = View.GONE
        wordCardContent = findViewById(R.id.wordCardContent)
        wordCardMask = findViewById(R.id.wordCardMask)
        wordCardRevealArea = findViewById(R.id.wordCardRevealArea)

        moviePlayer = findViewById(R.id.moviePlayer)
        intent.getStringExtra(EXTRA_VIDEO_URI)?.takeIf { it.isNotBlank() }?.let {
            moviePlayer.setVideo(Uri.parse(it))
            hasVideo = true
        }
        btnJumpToSentence = findViewById(R.id.btnJumpToSentence)
        btnNext = findViewById(R.id.btnNext)
        btnPrevious = findViewById(R.id.btnPrevious)
        tvProgress = findViewById(R.id.tvFlashcardProgress)
        movieId = intent.getIntExtra(EXTRA_MOVIE_ID, 0)
        currentIndex = savedInstanceState?.getInt(STATE_INDEX) ?: 0
        // 后端没有返回音标、词性或例句翻译，不显示原来的示例内容。
        listOf(R.id.tvPhonetic, R.id.tvPos, R.id.tvSentenceMeaning).forEach {
            findViewById<View>(it).visibility = View.GONE
        }

        wordCardRevealArea.setOnClickListener {
            if (!hasWordContent || loading || loadFailed) return@setOnClickListener
            isWordRevealed = !isWordRevealed
            updateWordCardRevealState()
        }

        btnJumpToSentence.setOnClickListener {
            if (loading || loadFailed || !hasVideo) return@setOnClickListener
            val word = words.getOrNull(currentIndex) ?: return@setOnClickListener
            if (!word.startTime.isFinite()) return@setOnClickListener
            // 只有主动查看视频提示时才跳转，后端返回的句子时间单位为秒。
            moviePlayer.seekTo((word.startTime.coerceAtLeast(0.0) * 1000).toLong())
            moviePlayer.play()
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

    override fun onStart() {
        super.onStart()
        moviePlayer.initialize()
    }

    override fun onStop() {
        moviePlayer.release()
        super.onStop()
    }

    override fun onDestroy() {
        moviePlayer.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_INDEX, currentIndex)
        super.onSaveInstanceState(outState)
    }

    private fun loadWords() {
        // 加载、错误和空结果是状态提示，不遮住这些提示。
        hasWordContent = false
        tvSentence.text = ""
        tvSentence.visibility = View.GONE
        tvSentenceStatus.visibility = View.GONE
        sentenceDivider.visibility = View.GONE
        resetWordRevealState()
        btnPrevious.isEnabled = false
        btnJumpToSentence.isEnabled = false
        if (movieId <= 0) {
            tvWord.text = "缺少视频信息"
            tvMeaning.text = "请从上传页面打开闪卡"
            tvProgress.text = ""
            btnNext.text = "返回"
            return
        }
        loading = true
        loadFailed = false
        tvWord.text = "正在加载…"
        tvMeaning.text = ""
        tvProgress.text = ""
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

    private fun resetWordRevealState() {
        isWordRevealed = false
        updateWordCardRevealState()
    }

    private fun updateWordCardRevealState() {
        val concealed = hasWordContent && !isWordRevealed
        wordCardRevealArea.isEnabled = hasWordContent
        wordCardRevealArea.contentDescription = if (!hasWordContent) null else if (concealed) {
            "点击显示释义和例句"
        } else {
            "点击隐藏释义和例句"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = 16f * resources.displayMetrics.density
            wordCardContent.setRenderEffect(
                if (concealed) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
            )
            wordCardMask.alpha = 0.45f
        } else {
            // 旧版本使用高不透明度遮罩，避免文字仍可辨认。
            wordCardMask.alpha = 0.98f
        }
        wordCardMask.visibility = if (concealed) View.VISIBLE else View.INVISIBLE
        wordCardContent.importantForAccessibility = if (concealed) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun highlightSentence(sentence: String, targetWord: String): CharSequence {
        val target = targetWord.trim()
        if (sentence.isBlank() || target.isEmpty()) return sentence
        val highlighted = SpannableString(sentence)
        // 忽略大小写，只标记完整单词，避免把其他单词中的相同字母误标出来。
        val pattern = Regex(
            "(?<![\\p{L}\\p{N}_'’])${Regex.escape(target)}(?![\\p{L}\\p{N}_'’])",
            RegexOption.IGNORE_CASE
        )
        pattern.findAll(sentence).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            highlighted.setSpan(ForegroundColorSpan(Color.rgb(52, 120, 246)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            highlighted.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return highlighted
    }

    private fun renderWord() {
        val word = words.getOrNull(currentIndex)
        tvSentence.text = highlightSentence(word?.sentenceText.orEmpty(), word?.word.orEmpty())
        tvSentence.visibility = if (word?.sentenceText.isNullOrBlank()) View.GONE else View.VISIBLE
        sentenceDivider.visibility = tvSentence.visibility
        tvSentenceStatus.visibility = if (word != null && word.sentenceText.isNullOrBlank()) View.VISIBLE else View.GONE
        hasWordContent = word != null
        // 所有换词入口统一经过这里，每次都开始新的揭示状态。
        resetWordRevealState()
        btnPrevious.isEnabled = word != null && currentIndex > 0
        btnJumpToSentence.isEnabled = hasVideo && word != null && word.startTime.isFinite()
        if (word == null) {
            tvWord.text = "暂无匹配单词"
            tvMeaning.text = "视频处理已完成，但没有匹配到所选范围内的单词"
            tvProgress.text = "0 个单词"
            btnNext.text = "返回"

            moviePlayer.pause()
            return
        }
        tvWord.text = word.word
        tvMeaning.text = word.meaning
        tvProgress.text = "${currentIndex + 1} / ${words.size}"
        // 换词时暂停提示视频，保留播放位置，避免连续切卡触发频繁跳转。
        moviePlayer.pause()

        btnNext.text = if (currentIndex == words.lastIndex) "完成学习" else "下一个单词 →"
    }
}
