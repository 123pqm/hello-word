package com.example.helloword

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.FavoriteWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class FavoritesActivity : AppCompatActivity() {
    companion object { internal var apiService = RetrofitClient.apiService }
    private lateinit var list: ListView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var status: TextView
    private lateinit var empty: View
    private lateinit var more: Button
    private val rows = mutableListOf<FavoriteWord>()
    private val adapter = FavoriteAdapter()
    private var nextId: Long? = null
    private var loading = false
    private var deleting = false
    private var retryRefresh: Boolean? = null
    private var owner: Int? = null
    private var account: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner = RetrofitClient.userId
        account = RetrofitClient.account
        if (owner == null || account == null || RetrofitClient.token.isNullOrBlank()) { login(); return }
        setContentView(R.layout.activity_favorites)
        val root = findViewById<View>(R.id.favoritesRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<View>(R.id.favoritesBack).setOnClickListener { finish() }
        list = findViewById(R.id.favoritesWords)
        refresh = findViewById(R.id.favoritesRefresh)
        status = findViewById(R.id.favoritesStatus)
        empty = findViewById(R.id.favoritesEmpty)
        more = findViewById(R.id.favoritesMore)
        list.adapter = adapter
        refresh.setColorSchemeColors(android.graphics.Color.rgb(52, 120, 246))
        refresh.setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
        refresh.setOnRefreshListener { load(true) }
        more.setOnClickListener { load(retryRefresh ?: false) }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            private var scrolling = false
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                scrolling = state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (scrolling) loadAtBottom()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (scrolling) loadAtBottom()
            }
            private fun loadAtBottom() {
                if (rows.isNotEmpty() && nextId != null && retryRefresh == null && !list.canScrollVertically(1)) load(false)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) load(true)
    }

    private fun sameAccount(): Boolean = owner == RetrofitClient.userId && account == RetrofitClient.account

    private fun login() {
        Toast.makeText(this, "请重新登录后查看收藏", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun summary() {
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        status.text = when {
            rows.isEmpty() -> "下拉刷新"
            nextId == null -> "共 ${rows.size} 个收藏 · 已全部加载"
            else -> "已加载 ${rows.size} 个收藏 · 滑到底部继续加载"
        }
    }

    private fun updateControls() {
        refresh.isEnabled = !loading && !deleting
        more.isEnabled = !loading && !deleting
        more.visibility = if (nextId != null || retryRefresh != null) View.VISIBLE else View.GONE
        more.text = if (retryRefresh != null) "重新加载" else "加载更多"
        adapter.notifyDataSetChanged()
    }

    private fun load(reset: Boolean) {
        if (loading || deleting || (!reset && nextId == null)) return
        if (!sameAccount()) { finish(); return }
        val token = RetrofitClient.token ?: run { login(); return }
        loading = true
        retryRefresh = null
        updateControls()
        refresh.isRefreshing = true
        empty.visibility = View.GONE
        status.text = "正在加载收藏…"
        lifecycleScope.launch {
            try {
                val response = apiService.favoriteWords("Bearer $token", beforeId = if (reset) null else nextId)
                response.errorBody()?.close()
                if (!sameAccount()) { finish(); return@launch }
                if (response.code() == 401) { login(); return@launch }
                val page = response.body()?.takeIf { it.code == 200 }?.data
                if (!response.isSuccessful || page == null) throw IllegalStateException("收藏加载失败，请重试")
                if (reset) rows.clear()
                val known = rows.map { it.id }.toSet()
                rows.addAll(page.items.filter { it.id !in known })
                nextId = page.nextBeforeId
                if (reset) list.setSelection(0)
                summary()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (sameAccount()) {
                    status.text = "收藏加载失败，请检查网络后重试"
                    retryRefresh = reset
                }
            } finally {
                loading = false
                refresh.isRefreshing = false
                updateControls()
            }
        }
    }

    private fun confirmDelete(word: FavoriteWord) {
        if (loading || deleting || !sameAccount()) return
        AlertDialog.Builder(this).setTitle("删除收藏？")
            .setMessage("将“${word.word}”移出我的收藏，电影里的原单词和例句会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> delete(word) }.show()
    }

    private fun delete(word: FavoriteWord) {
        if (loading || deleting || !sameAccount()) return
        val token = RetrofitClient.token ?: run { login(); return }
        deleting = true
        status.text = "正在删除收藏…"
        updateControls()
        lifecycleScope.launch {
            try {
                val response = apiService.deleteFavorite(word.word, "Bearer $token")
                response.errorBody()?.close()
                if (!sameAccount()) { finish(); return@launch }
                if (response.code() == 401) { login(); return@launch }
                if (!response.isSuccessful) throw IllegalStateException("删除失败")
                rows.removeAll { it.id == word.id }
                summary()
                Toast.makeText(this@FavoritesActivity, "已删除收藏", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (sameAccount()) {
                    status.text = "删除失败，请重试；也可下拉刷新确认状态"
                    Toast.makeText(this@FavoritesActivity, status.text, Toast.LENGTH_LONG).show()
                }
            } finally {
                deleting = false
                updateControls()
            }
        }
    }

    private inner class FavoriteAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_favorite_word, parent, false)
            val word = getItem(position)
            view.findViewById<TextView>(R.id.favoriteEnglish).text = word.word
            view.findViewById<TextView>(R.id.favoriteMeaning).text = word.meaning?.takeIf { it.isNotBlank() } ?: "暂无释义"
            view.findViewById<TextView>(R.id.favoriteSentence).apply {
                text = word.sentenceText.orEmpty()
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.favoriteSource).apply {
                text = word.sourceMovieName?.takeIf { it.isNotBlank() }?.let { "来自 $it" }.orEmpty()
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.favoriteDelete).apply {
                contentDescription = "删除收藏 ${word.word}"
                isEnabled = !loading && !deleting
                alpha = if (isEnabled) 1f else 0.4f
                setOnClickListener { confirmDelete(word) }
            }
            return view
        }
    }
}
