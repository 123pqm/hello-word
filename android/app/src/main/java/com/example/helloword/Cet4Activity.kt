package com.example.helloword

import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.example.helloword.model.Cet4Word
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.Cet4Pages
import com.example.helloword.model.UploadVocabulary
import com.example.helloword.model.Wordbooks
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

class Cet4Activity : AppCompatActivity() {
    companion object {
        const val EXTRA_READ_ONLY = "cet4_read_only"
    }

    private var readOnly = false
    private val bookId get() = intent.getIntExtra(UploadVocabulary.EXTRA_BOOK_ID, Wordbooks.CET4)
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var confirm: Button
    private lateinit var list: ListView
    private val pages = Cet4Pages()
    private val adapter = WordAdapter()
    private var query = ""
    private val searchResults = mutableListOf<Cet4Word>()
    private var nextOffset: Int? = 0
    private var requestJob: Job? = null
    private var debounceJob: Job? = null
    private var generation = 0
    private val visibleWords get() = if (query.isBlank()) pages.words else searchResults
    private var loading = false
    private var failed = false
    private var scrolling = false
    private var saving = false
    private val selectionKey get() = "selected_ids_${RetrofitClient.account.orEmpty()}" +
        if (bookId == Wordbooks.CET4) "" else "_book_$bookId"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)
        if (RetrofitClient.token.isNullOrBlank()) { returnToLogin(); return }
        setContentView(R.layout.activity_cet4)
        if (bookId !in Wordbooks.ids) { finish(); return }
        findViewById<TextView>(R.id.wordbookTitle).text = Wordbooks.name(bookId)
        findViewById<TextView>(R.id.wordbookSource).apply {
            visibility = if (bookId == Wordbooks.CET4) View.GONE else View.VISIBLE
            setOnClickListener {
                val description = TextView(this@Cet4Activity).apply {
                    text = Wordbooks.NETEM_SOURCE
                    setPadding(32, 24, 32, 24)
                    autoLinkMask = android.text.util.Linkify.WEB_URLS
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
                AlertDialog.Builder(this@Cet4Activity).setTitle("词库来源与说明")
                    .setView(description).setPositiveButton("关闭", null).show()
            }
        }
        findViewById<Button>(R.id.wordbookUseWhole).apply {
            visibility = if (readOnly) View.GONE else View.VISIBLE
            setOnClickListener {
                startActivity(Intent(this@Cet4Activity, movie_upload::class.java).apply {
                    putExtra(UploadVocabulary.EXTRA_SOURCE, UploadVocabulary.SOURCE_BOOK)
                    putExtra(UploadVocabulary.EXTRA_BOOK_ID, bookId)
                })
            }
        }
        val root = findViewById<View>(R.id.cet4Root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        progress = findViewById(R.id.cet4Progress)
        status = findViewById(R.id.cet4Status)
        retry = findViewById(R.id.cet4Retry)
        confirm = findViewById(R.id.cet4Confirm)
        confirm.visibility = if (readOnly) View.GONE else View.VISIBLE
        confirm.isEnabled = !readOnly
        findViewById<View>(R.id.cet4TitleSpacer).visibility = if (readOnly) View.VISIBLE else View.GONE
        list = findViewById(R.id.cet4Words)
        val restored = savedInstanceState?.getIntArray("selected_ids")?.toList()
            ?: getSharedPreferences("cet4_selection", MODE_PRIVATE)
                .getStringSet(selectionKey, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
        if (!readOnly) pages.selectedIds.addAll(restored)
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                scrolling = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (scrolling) loadIfAtBottom()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (scrolling) loadIfAtBottom()
            }
        })
        findViewById<View>(R.id.cet4Back).setOnClickListener { finish() }
        retry.setOnClickListener { loadWords() }
        if (!readOnly) confirm.setOnClickListener { saveSelection() }
        val search = findViewById<EditText>(R.id.wordbookSearch)
        val clear = findViewById<View>(R.id.wordbookSearchClear)
        clear.setOnClickListener { search.text.clear() }
        search.doAfterTextChanged { text ->
            clear.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            val value = text.toString().trim()
            if (query == value) return@doAfterTextChanged
            query = value
            generation++
            requestJob?.cancel(); debounceJob?.cancel()
            loading = false; failed = false; scrolling = false
            searchResults.clear(); nextOffset = 0
            adapter.notifyDataSetChanged()
            // ListView can retain its previous item count until the next layout pass.
            // Never request position zero while the new search list is empty.
            if (adapter.count > 0) list.setSelection(0)
            retry.visibility = View.GONE
            if (query.isBlank()) {
                progress.visibility = View.GONE
                status.text = "已加载 ${pages.words.size} 个词条，滑到底部继续加载"
                if (pages.words.isEmpty()) loadWords()
            } else {
                status.text = "输入停止后搜索…"
                progress.visibility = View.GONE
                debounceJob = lifecycleScope.launch { delay(350); loadWords() }
            }
        }
        search.setText(savedInstanceState?.getString("search_query").orEmpty())
        updateConfirm()
        if (query.isBlank()) loadWords()
    }

    private fun loadIfAtBottom() {
        if (adapter.count > 0 && !list.canScrollVertically(1) && !failed) loadWords()
    }

    private fun loadWords() {
        if (loading || (if (query.isBlank()) pages.finished else nextOffset == null)) return
        if (RetrofitClient.token.isNullOrBlank()) { returnToLogin(); return }
        loading = true
        failed = false
        progress.visibility = View.VISIBLE
        retry.visibility = View.GONE
        val requestGeneration = generation
        val requestQuery = query
        status.text = if (query.isBlank()) "正在加载第 ${pages.nextStart}–${pages.nextEnd} 个单词…" else "正在搜索单词…"
        requestJob = lifecycleScope.launch {
            try {
                if (requestQuery.isNotBlank()) {
                    val result = RetrofitClient.apiService.searchWords(bookId, requestQuery, nextOffset ?: return@launch)
                    if (generation != requestGeneration) return@launch
                    val existing = searchResults.mapTo(hashSetOf()) { it.id }
                    searchResults.addAll(result.data.filter { existing.add(it.id) })
                    nextOffset = if (result.hasMore) result.nextOffset else null
                    adapter.notifyDataSetChanged()
                    status.text = when {
                        searchResults.isEmpty() -> "没有找到对应单词，试试其他拼写"
                        nextOffset == null -> "已显示全部 ${searchResults.size} 个搜索结果"
                        else -> "已加载 ${searchResults.size} 个搜索结果，滑到底部继续加载"
                    }
                    return@launch
                }
                val result = RetrofitClient.apiService.wordbookWords(
                    bookId = bookId, startIndex = pages.nextStart, endIndex = pages.nextEnd)
                if (generation != requestGeneration) return@launch
                val items = result.data ?: throw IllegalStateException("Missing data")
                pages.append(items)
                adapter.notifyDataSetChanged()
                status.text = when {
                    pages.words.isEmpty() -> "暂时没有单词"
                    pages.finished -> "已加载 ${pages.words.size} 个单词，已到底部"
                    else -> "已加载 ${pages.words.size}/${result.total ?: "—"} 个词条，滑到底部继续加载"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpException) {
                if (generation != requestGeneration) return@launch
                if (error.code() == 401) returnToLogin()
                else showError(when (error.code()) {
                    422 -> "请求参数未通过校验，请重试"
                    in 500..599 -> "服务器暂时无法加载单词，请稍后重试"
                    else -> "加载失败（${error.code()}），请重试"
                })
            } catch (error: IOException) {
                if (generation != requestGeneration) return@launch
                showError("连接失败，请确认网络及后端服务已开启")
            } catch (error: Exception) {
                if (generation != requestGeneration) return@launch
                showError("单词数据解析失败，请重试")
            } finally {
                if (generation == requestGeneration) {
                    loading = false
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private fun updateConfirm() { confirm.text = "确认(${pages.selectedIds.size})" }

    private fun saveSelection() {
        if (readOnly || saving) return
        if (pages.selectedIds.isEmpty()) {
            Toast.makeText(this, "请至少选择一个单词", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedIds = pages.selectedIds.toIntArray()
        val selected = pages.selectedIds.map { it.toString() }.toSet()
        val key = selectionKey
        saving = true
        confirm.isEnabled = false
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    getSharedPreferences("cet4_selection", MODE_PRIVATE)
                        .edit().putStringSet(key, selected).commit()
                }
                Toast.makeText(this@Cet4Activity,
                    if (success) "已保存 ${selected.size} 个单词到本机" else "保存失败，请重试",
                    Toast.LENGTH_SHORT).show()
                if (success) {
                    startActivity(Intent(this@Cet4Activity, movie_upload::class.java).apply {
                        putExtra(UploadVocabulary.EXTRA_BOOK_ID, bookId)
                        putExtra(UploadVocabulary.EXTRA_SOURCE, UploadVocabulary.SOURCE_WORDS)
                        putExtra(UploadVocabulary.EXTRA_WORD_IDS, selectedIds)
                    })
                }
            } finally {
                saving = false
                confirm.isEnabled = true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("search_query", query)
        outState.putIntArray("selected_ids", pages.selectedIds.toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun showError(message: String) {
        failed = true
        status.text = message
        retry.visibility = View.VISIBLE
    }

    private fun returnToLogin() {
        RetrofitClient.token = null
        Toast.makeText(this, "请重新登录后查看单词", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private inner class WordAdapter : BaseAdapter() {
        override fun getCount() = visibleWords.size
        override fun getItem(position: Int) = visibleWords[position]
        override fun getItemId(position: Int) =
            visibleWords.getOrNull(position)?.id?.toLong() ?: AdapterView.INVALID_ROW_ID
        override fun hasStableIds() = true
        override fun areAllItemsEnabled() = !readOnly
        override fun isEnabled(position: Int) = !readOnly
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_cet4_word, parent, false)
            val word = getItem(position)
            row.findViewById<TextView>(R.id.wordEnglish).text = word.word
            row.findViewById<TextView>(R.id.wordMeaning).text = word.meaning
            row.findViewById<TextView>(R.id.wordPos).apply {
                text = word.frequency?.let { "语料词频：$it" } ?: word.pos.orEmpty()
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            val check = row.findViewById<CheckBox>(R.id.wordSelected)
            // 复用行时移除旧监听，防止勾选状态串到其他单词。
            check.setOnCheckedChangeListener(null)
            check.visibility = if (readOnly) View.GONE else View.VISIBLE
            check.isEnabled = !readOnly
            check.isChecked = word.id in pages.selectedIds
            check.contentDescription = "选择 ${word.word}"
            if (readOnly) {
                row.setOnClickListener(null)
                row.isClickable = false
                return row
            }
            check.setOnCheckedChangeListener { _, checked ->
                pages.select(word.id, checked)
                updateConfirm()
            }
            row.setOnClickListener { check.performClick() }
            return row
        }
    }

}
