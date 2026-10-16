package com.example.helloword

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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
import com.example.helloword.api.RetrofitClient
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
    private lateinit var sentenceRevealArea: View
    private lateinit var sentenceContent: View
    private lateinit var sentenceMask: View
    private var isSentenceRevealed = false
    private var hasSentenceContent = false
    private lateinit var flashcardSettings: FlashcardSettings
    private var displayDefaults = FlashcardDisplayDefaults()
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
    private lateinit var videoSourceStatus: TextView
    private var activeVideoAccount: String? = null
    private lateinit var favoriteButton: android.widget.ImageButton
    private var favoriteState: Boolean? = null
    private var favoriteGeneration = 0
    private val pendingFavorites = mutableSetOf<String>()
    private var favoriteOwner: Int? = null
    companion object {
        internal var favoritesApi = RetrofitClient.apiService
        const val EXTRA_MOVIE_ID = "flashcard_movie_id"
        const val EXTRA_VIDEO_URI = "flashcard_video_uri"
        private const val STATE_INDEX = "flashcard_index"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.word_flashcard)
        favoriteOwner = RetrofitClient.userId
        favoriteButton = findViewById(R.id.btnFavoriteWord)
        favoriteButton.setOnClickListener { toggleFavorite() }
        flashcardSettings = FlashcardSettings(this)
        val root = findViewById<View>(R.id.flashcardRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<View>(R.id.flashcardAi).setOnClickListener {
            moviePlayer.pause()
            startActivity(Intent(this, AiAssistantActivity::class.java).apply {
                val word = words.getOrNull(currentIndex)?.takeUnless { loading || loadFailed }
                if (word != null) {
                    putExtra(AiAssistantActivity.EXTRA_WORD, word.word)
                    putExtra(AiAssistantActivity.EXTRA_SENTENCE, word.sentenceText)
                }
            })
        }

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
        sentenceRevealArea = findViewById(R.id.sentenceRevealArea)
        sentenceContent = findViewById(R.id.sentenceContent)
        sentenceMask = findViewById(R.id.sentenceMask)

        moviePlayer = findViewById(R.id.moviePlayer)
        videoSourceStatus = findViewById(R.id.videoSourceStatus)
        moviePlayer.onPlaybackError = {
            hasVideo = false
            btnJumpToSentence.isEnabled = false
            videoSourceStatus.text = "服务器视频暂时无法播放，请检查网络或重新登录后重试。闪卡和例句仍可学习。"
        }
        btnJumpToSentence = findViewById(R.id.btnJumpToSentence)
        btnNext = findViewById(R.id.btnNext)
        btnPrevious = findViewById(R.id.btnPrevious)
        tvProgress = findViewById(R.id.tvFlashcardProgress)
        movieId = intent.getIntExtra(EXTRA_MOVIE_ID, 0)
        findViewById<View>(R.id.chooseLocalMovie).setOnClickListener {
            connectServerVideo()
        }
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

        sentenceRevealArea.setOnClickListener {
            if (!hasSentenceContent || loading || loadFailed) return@setOnClickListener
            isSentenceRevealed = !isSentenceRevealed
            updateWordCardRevealState()
        }

        btnJumpToSentence.setOnClickListener {
            if (loading || loadFailed || !hasVideo) return@setOnClickListener
            val word = words.getOrNull(currentIndex) ?: return@setOnClickListener
            if (!word.startTime.isFinite()) return@setOnClickListener
            // 只有主动查看视频提示时才跳转，后端返回的句子时间单位为秒。
            moviePlayer.seekTo((word.startTime.coerceAtLeast(0.0) * 1000).toLong())
            moviePlayer.play()
            moviePlayer.requestRectangleOnScreen(android.graphics.Rect(0, 0, moviePlayer.width, moviePlayer.height), false)
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
        if (movieId > 0) connectServerVideo()
    }

    override fun onStart() {
        super.onStart()
        if (activeVideoAccount == null || activeVideoAccount == RetrofitClient.account) moviePlayer.initialize()
        else moviePlayer.release()
    }

    override fun onStop() {
        moviePlayer.release()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (flashcardSettings.read() != displayDefaults) resetWordRevealState()
        if (hasWordContent && !loading && !loadFailed) loadFavoriteState()
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
        favoriteGeneration++
        favoriteButton.isEnabled = false
        favoriteButton.contentDescription = "收藏当前单词"
        hasSentenceContent = false
        sentenceRevealArea.visibility = View.GONE
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
        displayDefaults = flashcardSettings.read()
        isWordRevealed = displayDefaults.showMeaning
        isSentenceRevealed = displayDefaults.showSentence
        updateWordCardRevealState()
    }

    private fun updateWordCardRevealState() {
        updateRevealArea(wordCardRevealArea, wordCardContent, wordCardMask, hasWordContent, isWordRevealed, "单词释义")
        updateRevealArea(sentenceRevealArea, sentenceContent, sentenceMask, hasSentenceContent, isSentenceRevealed, "例句")
    }

    private fun updateRevealArea(area: View, content: View, mask: View, hasContent: Boolean, revealed: Boolean, label: String) {
        val concealed = hasContent && !revealed
        area.isEnabled = hasContent
        area.contentDescription = if (!hasContent) null else if (concealed) {
            "点击显示$label"
        } else {
            "点击隐藏$label"
        }
        // 内容和提示按顺序布局，并且只显示其中一个，避免遮罩与文字重叠。
        content.alpha = if (concealed) 0f else 1f
        content.visibility = if (concealed) View.GONE else View.VISIBLE
        mask.visibility = if (concealed) View.VISIBLE else View.GONE
        content.importantForAccessibility = if (concealed) {
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

    private fun connectServerVideo() {
        hasVideo = false
        btnJumpToSentence.isEnabled = false
        moviePlayer.clearVideo()
        val token = RetrofitClient.token
        if (movieId <= 0 || token.isNullOrBlank() || !RetrofitClient.hasValidSession()) {
            videoSourceStatus.text = "登录已过期，请重新登录后播放服务器视频。"
            return
        }
        activeVideoAccount = RetrofitClient.account
        hasVideo = true
        moviePlayer.setVideo(Uri.parse(RetrofitClient.moviePlaybackUrl(movieId)),
            mapOf("Authorization" to "Bearer $token"))
        videoSourceStatus.text = "使用服务器视频，按需加载，无需读取手机原文件。"
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) moviePlayer.initialize()
        else moviePlayer.release()
        btnJumpToSentence.isEnabled = hasVideo && !loading && !loadFailed &&
            words.getOrNull(currentIndex)?.startTime?.isFinite() == true
    }

    private fun renderWord() {
        val word = words.getOrNull(currentIndex)
        loadFavoriteState()
        tvSentence.text = highlightSentence(word?.sentenceText.orEmpty(), word?.word.orEmpty())
        hasSentenceContent = !word?.sentenceText.isNullOrBlank()
        sentenceRevealArea.visibility = if (hasSentenceContent) View.VISIBLE else View.GONE
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

    private fun currentFavoriteWord(): String? = words.getOrNull(currentIndex)?.word
        ?.trim()?.lowercase(java.util.Locale.ROOT)

    private fun showFavoriteState(saved: Boolean) {
        favoriteState = saved
        favoriteButton.contentDescription = if (saved) "取消收藏当前单词" else "收藏当前单词"
        favoriteButton.isSelected = saved
        favoriteButton.isEnabled = true
    }

    private fun favoriteError(error: Exception) {
        android.widget.Toast.makeText(this, error.localizedMessage ?: "收藏操作失败，请重试",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun loadFavoriteState() {
        val generation = ++favoriteGeneration
        favoriteState = null
        favoriteButton.isEnabled = false
        favoriteButton.isSelected = false
        val word = currentFavoriteWord() ?: run { favoriteButton.contentDescription = "收藏当前单词"; return }
        val token = RetrofitClient.token
        val owner = RetrofitClient.userId
        if (owner == null || owner != favoriteOwner || token.isNullOrBlank()) {
            favoriteButton.contentDescription = "请重新登录后收藏"
            return
        }
        if (word in pendingFavorites) { favoriteButton.contentDescription = "正在保存收藏"; return }
        favoriteButton.contentDescription = "正在加载收藏状态"
        lifecycleScope.launch {
            try {
                val response = favoritesApi.favoriteStatus(word, "Bearer $token")
                response.errorBody()?.close()
                val body = response.body()
                if (!response.isSuccessful || body?.code != 200 || body.data == null) {
                    throw IllegalStateException(if (response.code() == 401) "登录已失效，请重新登录" else "收藏状态加载失败，请重试")
                }
                if (generation == favoriteGeneration && owner == RetrofitClient.userId) showFavoriteState(body.data.isFavorite)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == favoriteGeneration && owner == RetrofitClient.userId) {
                    favoriteButton.contentDescription = "重新加载当前单词的收藏状态"
                    favoriteButton.isEnabled = true
                }
            }
        }
    }

    private fun toggleFavorite() {
        if (loading || loadFailed) return
        val word = currentFavoriteWord() ?: return
        val saved = favoriteState ?: run { loadFavoriteState(); return }
        val owner = RetrofitClient.userId ?: return
        val token = RetrofitClient.token ?: return
        if (owner != favoriteOwner || !pendingFavorites.add(word)) return
        ++favoriteGeneration // Ignore any status read started before this write.
        favoriteButton.isEnabled = false
        favoriteButton.contentDescription = "正在保存收藏"
        lifecycleScope.launch {
            try {
                val response = if (saved) favoritesApi.deleteFavorite(word, "Bearer $token") else
                    favoritesApi.saveFavorite(com.example.helloword.model.FavoriteRequest(movieId, word), "Bearer $token")
                response.errorBody()?.close()
                if (!response.isSuccessful) throw IllegalStateException(when (response.code()) {
                    401 -> "登录已失效，请重新登录"
                    404 -> "来源电影或单词已删除，无法收藏"
                    else -> "收藏操作失败，请重试"
                })
                if (owner == RetrofitClient.userId && currentFavoriteWord() == word) showFavoriteState(!saved)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (owner == RetrofitClient.userId && currentFavoriteWord() == word) {
                    favoriteError(error)
                    // 网络中断时服务端可能已完成，重新查询后再允许下一次操作。
                    pendingFavorites.remove(word)
                    loadFavoriteState()
                }
            } finally {
                pendingFavorites.remove(word)
            }
        }
    }
}
