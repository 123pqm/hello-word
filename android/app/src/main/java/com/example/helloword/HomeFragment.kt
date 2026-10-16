package com.example.helloword

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.MovieHistoryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.view_home) {
    companion object { internal var apiService = RetrofitClient.apiService }
    private val refreshRequests = MutableStateFlow(0)
    private var recentMovie: MovieHistoryItem? = null
    private var recentOwner: Int? = null
    private var canRetry = false
    private var coverJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val calendar = view.findViewById<LinearLayout>(R.id.homeCheckinDays)
        repeat(7) { index ->
            val day = layoutInflater.inflate(R.layout.item_home_checkin, calendar, false)
            day.findViewById<TextView>(R.id.checkinWeekday).text = listOf("一", "二", "三", "四", "五", "六", "日")[index]
            calendar.addView(day)
        }
        renderCheckin(view, CheckinState())
        view.findViewById<View>(R.id.homeMore).setOnClickListener {
            startActivity(Intent(requireContext(), MyMoviesActivity::class.java))
        }

        view.findViewById<View>(R.id.homeNewTask).setOnClickListener {
            startActivity(Intent(requireContext(), select_book::class.java))
        }
        showRecentMessage(view, "正在加载最近学习…", "正在查询电影历史")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CheckinStore.state.collect { renderCheckin(view, it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // 每次回到首页重新查询；留在首页时也能发现处理完成、删除或其他设备的新上传。
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    refreshRequests.collectLatest {
                        showRecentMessage(view, "正在加载最近学习…", "正在查询电影历史")
                        while (true) delay(refreshRecent(view))
                    }
                } finally {
                    coverJob?.cancel()
                }
            }
        }
        view.findViewById<View>(R.id.homeRecentLesson).setOnClickListener {
            if (canRetry) {
                refreshRequests.value++
                return@setOnClickListener
            }
            val movie = recentMovie ?: return@setOnClickListener
            if (recentOwner != RetrofitClient.userId) {
                showRecentMessage(view, "正在加载最近学习…", "正在查询电影历史")
                refreshRequests.value++
                return@setOnClickListener
            }
            when (movie.status) {
                "completed" -> startActivity(Intent(requireContext(), WordFlashcardActivity::class.java)
                    .putExtra(WordFlashcardActivity.EXTRA_MOVIE_ID, movie.id))
                "pending", "processing" -> {
                    Toast.makeText(context, "电影仍在处理中，完成后可查看闪卡", Toast.LENGTH_SHORT).show()
                    refreshRequests.value++
                }
                else -> startActivity(Intent(requireContext(), MyMoviesActivity::class.java))
            }
        }
    }

    private suspend fun refreshRecent(view: View): Long {
        val owner = RetrofitClient.userId
        val token = RetrofitClient.token
        if (owner == null || token.isNullOrBlank()) {
            showRecentMessage(view, "请登录后查看最近学习", "电影历史按账号保存")
            return 30_000L
        }
        try {
            val response = apiService.movieHistory(limit = 1)
            response.errorBody()?.close()
            if (owner != RetrofitClient.userId || token != RetrofitClient.token) {
                showRecentMessage(view, "正在加载最近学习…", "正在查询电影历史")
                return 1_000L
            }
            val body = response.body()
            if (response.code() == 401) {
                showRecentMessage(view, "登录已失效", "请重新登录后查看电影历史")
                return 30_000L
            }
            if (!response.isSuccessful || body?.code != 200 || body.data == null) {
                throw IllegalStateException("无法读取电影历史")
            }
            val movie = body.data.items.firstOrNull()
            val changed = recentOwner != owner || recentMovie?.id != movie?.id
            recentOwner = owner
            recentMovie = movie
            canRetry = false
            renderRecent(view, movie)
            if (changed) {
                coverJob?.cancel()
                view.findViewById<ImageView>(R.id.homeRecentCover).setImageResource(android.R.drawable.ic_menu_gallery)
                if (movie != null) coverJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val bitmap = MovieCoverRepository.load(movie.id, owner)
                        if (bitmap != null && owner == RetrofitClient.userId && recentOwner == owner &&
                            recentMovie?.id == movie.id) view.findViewById<ImageView>(R.id.homeRecentCover).setImageBitmap(bitmap)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { /* 封面失败保留占位图，不影响电影信息。 */ }
                }
            }
            return if (movie?.status in setOf("pending", "processing")) 3_000L else 30_000L
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            showRecentMessage(view, "最近学习加载失败", "点击重试，或稍后自动刷新", retry = true)
            return 10_000L
        }
    }

    private fun showRecentMessage(view: View, title: String, detail: String, retry: Boolean = false) {
        coverJob?.cancel()
        recentMovie = null
        recentOwner = null
        canRetry = retry
        view.findViewById<View>(R.id.homeRecentLesson).isEnabled = retry
        view.findViewById<TextView>(R.id.homeRecentTitle).text = title
        view.findViewById<TextView>(R.id.homeRecentDetails).text = detail
        view.findViewById<TextView>(R.id.homeLessonProgressLabel).text = if (retry) "点击重试" else ""
        view.findViewById<ProgressBar>(R.id.homeLessonProgress).visibility = View.GONE
        view.findViewById<ImageView>(R.id.homeRecentCover).setImageResource(android.R.drawable.ic_menu_gallery)
    }

    private fun renderRecent(view: View, movie: MovieHistoryItem?) {
        view.findViewById<View>(R.id.homeRecentLesson).isEnabled = movie != null
        view.findViewById<TextView>(R.id.homeRecentTitle).text = if (movie == null) "暂无最近学习" else movie.fileName ?: "未命名电影"
        view.findViewById<TextView>(R.id.homeRecentDetails).text = if (movie == null) "上传电影后，这里会显示最新记录"
            else movie.createdAt?.replace('T', ' ')?.let { "上传于 $it" } ?: "上传时间未知"
        val busy = movie?.status in setOf("pending", "processing")
        view.findViewById<ProgressBar>(R.id.homeLessonProgress).apply {
            visibility = if (movie == null) View.GONE else View.VISIBLE
            isIndeterminate = busy
            progress = if (movie?.status == "completed") 100 else 0
        }
        view.findViewById<TextView>(R.id.homeLessonProgressLabel).text = when {
            movie == null -> ""
            movie.status == "pending" -> "等待分析"
            movie.status == "processing" -> "分析中"
            movie.status == "completed" -> "查看闪卡"
            movie.status == "failed" -> "分析失败 · 查看历史"
            else -> "状态未知 · 查看历史"
        }
    }

    private fun renderCheckin(view: View, state: CheckinState) {
        val sameUser = state.userId == RetrofitClient.userId
        val data = state.data.takeIf { sameUser }
        view.findViewById<TextView>(R.id.homeStreakDays).text = data?.streakDays?.toString() ?: "—"
        view.findViewById<TextView>(R.id.homeCheckinSummary).text = when {
            data != null -> "本周已登录 ${data.weekCount} 天 · 今天已打卡"
            sameUser && state.error != null -> state.error
            else -> "正在同步打卡记录…"
        }
        val calendar = view.findViewById<LinearLayout>(R.id.homeCheckinDays)
        repeat(7) { index ->
            val day = data?.days?.getOrNull(index)
            val cell = calendar.getChildAt(index)
            cell.findViewById<TextView>(R.id.checkinDate).text = day?.date?.takeLast(5)?.replace('-', '/') ?: "—"
            cell.findViewById<TextView>(R.id.checkinWeekday).apply {
                setTextColor(Color.parseColor(if (day?.isToday == true) "#3478F6" else "#737B87"))
                setTypeface(null, if (day?.isToday == true) Typeface.BOLD else Typeface.NORMAL)
            }
            cell.findViewById<ImageView>(R.id.checkinMark).apply {
                setImageResource(if (day?.checked == true) R.drawable.vh_check else R.drawable.vh_unchecked)
                contentDescription = day?.let { "${it.date}，${if (it.checked) "已打卡" else "未打卡"}${if (it.isToday) "，今天" else ""}" }
                    ?: "打卡记录未加载"
            }
        }
    }
}
