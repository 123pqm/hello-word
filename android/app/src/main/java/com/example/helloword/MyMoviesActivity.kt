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
import androidx.appcompat.app.AlertDialog
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MyMoviesActivity : AppCompatActivity() {
    companion object { internal var apiService = RetrofitClient.apiService }
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var message: TextView
    private lateinit var more: Button
    private val rows = mutableListOf<MovieHistoryItem>()
    private lateinit var adapter: MovieHistoryAdapter
    private var nextId: Int? = null
    private var loading = false
    private var deleting = false
    private lateinit var refreshLayout: SwipeRefreshLayout

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
        refreshLayout = findViewById(R.id.historyRefresh)
        refreshLayout.setColorSchemeColors(android.graphics.Color.rgb(52, 120, 246))
        refreshLayout.setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
        refreshLayout.setOnRefreshListener { load(refresh = true) }
        adapter = MovieHistoryAdapter(rows, lifecycleScope, ::openMovie, ::confirmDelete)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        more.setOnClickListener { load(refresh = false) }
    }

    override fun onResume() {
        super.onResume()
        load(refresh = true)
    }

    private fun load(refresh: Boolean) {
        if (loading || deleting) return
        val owner = RetrofitClient.userId
        if (owner == null || RetrofitClient.token.isNullOrBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        loading = true
        refreshLayout.isEnabled = false
        refreshLayout.isRefreshing = refresh
        more.isEnabled = false
        empty.visibility = View.GONE
        message.text = "正在加载电影历史…"
        lifecycleScope.launch {
            try {
                val response = apiService.movieHistory(beforeId = if (refresh) null else nextId)
                response.errorBody()?.close()
                if (owner != RetrofitClient.userId) {
                    finish()
                    return@launch
                }
                val body = response.body()
                val page = body?.data
                if (!response.isSuccessful || body?.code != 200 || page == null) {
                    throw IllegalStateException("读取失败（HTTP ${response.code()}），请在顶部下拉重试")
                }
                if (refresh) rows.clear()
                val start = rows.size
                val knownIds = rows.map { it.id }.toSet()
                rows.addAll(page.items.filter { it.id !in knownIds })
                if (refresh) adapter.notifyDataSetChanged() else adapter.notifyItemRangeInserted(start, rows.size - start)
                nextId = page.nextBeforeId
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                updateSummary()
                more.text = "加载更多"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.text = error.localizedMessage ?: "加载失败，请在顶部下拉重试"
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                if (!refresh) more.text = "重试加载更多"
            } finally {
                loading = false
                refreshLayout.isRefreshing = false
                refreshLayout.isEnabled = true
                more.isEnabled = true
                more.visibility = if (nextId == null) View.GONE else View.VISIBLE
            }
        }
    }

    private fun updateSummary() {
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        message.text = if (rows.isEmpty()) "当前账号暂无电影历史 · 下拉刷新" else
            "已加载 ${rows.size} 部 · 在顶部下拉刷新"
    }

    private fun confirmDelete(movie: MovieHistoryItem) {
        if (loading || deleting) return
        val owner = RetrofitClient.userId ?: return
        AlertDialog.Builder(this)
            .setTitle("删除电影？")
            .setMessage("将删除“${movie.fileName ?: "未命名电影"}”及其单词、例句记录，删除后无法恢复。手机中的原视频会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (owner == RetrofitClient.userId) deleteMovie(movie)
            }.show()
    }

    private fun deleteMovie(movie: MovieHistoryItem) {
        if (loading || deleting) return
        val owner = RetrofitClient.userId ?: return
        val account = RetrofitClient.account ?: return
        deleting = true
        refreshLayout.isEnabled = false
        more.isEnabled = false
        message.text = "正在删除电影…"
        lifecycleScope.launch {
            try {
                val response = apiService.deleteMovie(movie.id)
                response.errorBody()?.close()
                if (owner != RetrofitClient.userId || account != RetrofitClient.account) {
                    finish()
                    return@launch
                }
                // 重试时已删除的记录也视为完成。
                if (!response.isSuccessful && response.code() != 404) {
                    throw IllegalStateException("删除失败（HTTP ${response.code()}），请重试")
                }
                VideoUploadStore.forAccount(this@MyMoviesActivity, account).forgetDeletedMovie(movie.id)
                MovieCoverRepository.forget(movie.id, owner)
                withContext(Dispatchers.IO) {
                    android.util.AtomicFile(java.io.File(filesDir, "movie_flashcards/user_$owner/${movie.id}.json")).delete()
                }
                val index = rows.indexOfFirst { it.id == movie.id }
                if (index >= 0) { rows.removeAt(index); adapter.notifyItemRemoved(index) }
                updateSummary()
                Toast.makeText(this@MyMoviesActivity, "电影及关联单词已删除", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message.text = error.localizedMessage ?: "删除失败，请重试"
                Toast.makeText(this@MyMoviesActivity, message.text, Toast.LENGTH_LONG).show()
            } finally {
                deleting = false
                refreshLayout.isEnabled = true
                more.isEnabled = true
            }
        }
    }

    private fun openMovie(movie: MovieHistoryItem) {
        if (deleting) return
        if (movie.status != "completed") {
            Toast.makeText(this, "电影尚未分析成功，可刷新查看状态", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, WordFlashcardActivity::class.java).apply {
            putExtra(WordFlashcardActivity.EXTRA_MOVIE_ID, movie.id)

        })
    }
}

private class MovieHistoryAdapter(
    private val rows: List<MovieHistoryItem>,
    private val scope: CoroutineScope,
    private val onClick: (MovieHistoryItem) -> Unit,
    private val onDelete: (MovieHistoryItem) -> Unit
) : RecyclerView.Adapter<MovieHistoryAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.movieCover)
        var coverJob: Job? = null
        val name: TextView = view.findViewById(R.id.movieName)
        val info: TextView = view.findViewById(R.id.movieInfo)
        val status: TextView = view.findViewById(R.id.movieStatus)
        val delete: TextView = view.findViewById(R.id.deleteMovie)
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
        holder.delete.contentDescription = "删除${movie.fileName ?: "未命名电影"}"
        holder.delete.setOnClickListener { onDelete(movie) }
    }
}
