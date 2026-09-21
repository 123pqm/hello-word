package com.example.helloword

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.MovieHistoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class MyMoviesActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var message: TextView
    private lateinit var more: Button
    private val rows = mutableListOf<MovieHistoryItem>()
    private lateinit var adapter: MovieHistoryAdapter
    private var nextId: Int? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_movies)
        val root = findViewById<View>(R.id.topBar).parent as View
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        list = findViewById(R.id.movieRecyclerView)
        empty = findViewById(R.id.emptyView)
        message = findViewById(R.id.historyMessage)
        more = findViewById(R.id.historyLoadMore)
        adapter = MovieHistoryAdapter(rows, lifecycleScope, ::openMovie)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        more.setOnClickListener { load(refresh = nextId == null) }
    }

    override fun onResume() {
        super.onResume()
        load(refresh = true)
    }

    private fun load(refresh: Boolean) {
        if (loading) return
        val owner = RetrofitClient.userId
        if (owner == null || RetrofitClient.token.isNullOrBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        if (refresh) {
            rows.clear()
            adapter.notifyDataSetChanged()
            nextId = null
        }
        loading = true
        more.isEnabled = false
        empty.visibility = View.GONE
        message.text = "正在加载电影历史…"
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.apiService.movieHistory(beforeId = nextId)
                response.errorBody()?.close()
                if (owner != RetrofitClient.userId) {
                    finish()
                    return@launch
                }
                val body = response.body()
                val page = body?.data
                if (!response.isSuccessful || body?.code != 200 || page == null) {
                    throw IllegalStateException("读取失败（HTTP ${response.code()}），点击下方重试")
                }
                val start = rows.size
                val knownIds = rows.map { it.id }.toSet()
                rows.addAll(page.items.filter { it.id !in knownIds })
                adapter.notifyItemRangeInserted(start, rows.size - start)
                nextId = page.nextBeforeId
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                message.text = if (rows.isEmpty()) "当前账号暂无电影历史" else "已加载 ${rows.size} 部 · 点击已完成电影查看闪卡"
                more.text = if (nextId == null) "刷新" else "加载更多"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.text = error.localizedMessage ?: "加载失败，请重试"
                more.text = "重试"
            } finally {
                loading = false
                more.isEnabled = true
            }
        }
    }

    private fun openMovie(movie: MovieHistoryItem) {
        if (movie.status != "completed") {
            Toast.makeText(this, "电影尚未分析成功，可刷新查看状态", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, WordFlashcardActivity::class.java).apply {
            putExtra(WordFlashcardActivity.EXTRA_MOVIE_ID, movie.id)
            val account = RetrofitClient.account
            val session = account?.let { VideoUploadStore.forAccount(this@MyMoviesActivity, it).state.value }
            if (session?.result?.movieId == movie.id) {
                putExtra(WordFlashcardActivity.EXTRA_VIDEO_URI, session.uri)
            }
        })
    }
}

private class MovieHistoryAdapter(
    private val rows: List<MovieHistoryItem>,
    private val scope: CoroutineScope,
    private val onClick: (MovieHistoryItem) -> Unit
) : RecyclerView.Adapter<MovieHistoryAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.movieCover)
        var coverJob: Job? = null
        val name: TextView = view.findViewById(R.id.movieName)
        val info: TextView = view.findViewById(R.id.movieInfo)
        val status: TextView = view.findViewById(R.id.movieStatus)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_movie, parent, false)
    )
    override fun getItemCount() = rows.size
    override fun onViewRecycled(holder: Holder) {
        holder.coverJob?.cancel()
        holder.cover.setImageResource(android.R.drawable.ic_menu_gallery)
        super.onViewRecycled(holder)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val movie = rows[position]
        holder.coverJob?.cancel()
        holder.cover.setImageResource(android.R.drawable.ic_menu_gallery)
        holder.cover.contentDescription = "${movie.fileName ?: "电影"}封面"
        val owner = RetrofitClient.userId
        holder.coverJob = scope.launch {
            try {
                if (owner != null) {
                    val bitmap = MovieCoverRepository.load(movie.id, owner)
                    if (bitmap != null && RetrofitClient.userId == owner) holder.cover.setImageBitmap(bitmap)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 视频缺失或网络失败时保留占位图，不影响历史列表。
            }
        }
        holder.name.text = movie.fileName ?: "未命名电影"
        holder.info.text = movie.createdAt?.replace('T', ' ') ?: "上传时间未知"
        holder.status.text = when (movie.status) {
            "pending" -> "等待分析"
            "processing" -> "分析中"
            "completed" -> "分析完成 · 查看闪卡"
            "failed" -> "分析失败"
            else -> "状态未知"
        }
        holder.itemView.setOnClickListener { onClick(movie) }
    }
}
