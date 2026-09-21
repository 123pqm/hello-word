package com.example.helloword

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.appcompat.app.AppCompatActivity
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.UploadPhase
import com.example.helloword.model.UploadVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HomeFragment : Fragment(R.layout.view_home) {
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
        // Hydrate the global state from the existing account-scoped persistent upload session.
        RetrofitClient.account?.let { VideoUploadStore.forAccount(requireContext(), it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CheckinStore.state.collect { renderCheckin(view, it) } }
                launch { LearningStore.recentMovie.collect { renderRecent(view, it) } }
                launch {
                    LearningStore.recentMovie.map { it?.uri }.distinctUntilChanged().collectLatest { uri ->
                        val cover = view.findViewById<ImageView>(R.id.homeRecentCover)
                        cover.setImageResource(android.R.drawable.ic_menu_gallery)
                        if (!uri.isNullOrBlank()) {
                            val appContext = requireContext().applicationContext
                            val bitmap = withContext(Dispatchers.IO) {
                                val retriever = MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(appContext, Uri.parse(uri))
                                    if (android.os.Build.VERSION.SDK_INT >= 27) {
                                        retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 200, 264)
                                    } else retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                } catch (_: Exception) { null }
                                finally { retriever.release() }
                            }
                            if (bitmap != null) cover.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }
        view.findViewById<View>(R.id.homeRecentLesson).setOnClickListener {
            val account = RetrofitClient.account ?: return@setOnClickListener
            val store = VideoUploadStore.forAccount(requireContext(), account)
            val session = store.state.value ?: return@setOnClickListener
            val movieId = session.result?.movieId
            if (session.phase == UploadPhase.COMPLETED && movieId != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    FlashcardNavigation.open(requireActivity() as AppCompatActivity, store, movieId,
                        onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() })
                }
            } else {
                startActivity(Intent(requireContext(), movie_upload::class.java).apply {
                    val vocabulary = session.vocabulary
                    putExtra(UploadVocabulary.EXTRA_SOURCE,
                        if (vocabulary.mode == UploadVocabulary.ALL_CET4) UploadVocabulary.SOURCE_BOOK else UploadVocabulary.SOURCE_WORDS)
                    putExtra(UploadVocabulary.EXTRA_WORD_IDS, vocabulary.wordIds.toIntArray())
                })
            }
        }
    }

    private fun renderRecent(view: View, movie: MovieInfo?) {
        view.findViewById<View>(R.id.homeRecentLesson).isEnabled = movie != null
        view.findViewById<TextView>(R.id.homeRecentTitle).text = movie?.name ?: "暂无最近学习"
        view.findViewById<TextView>(R.id.homeRecentDetails).text = movie?.let {
            val size = when {
                it.size <= 0 -> "未知大小"
                it.size >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GB", it.size / (1024.0 * 1024 * 1024))
                else -> String.format(Locale.getDefault(), "%.1f MB", it.size / (1024.0 * 1024))
            }
            "${it.duration} · $size"
        } ?: "选择视频后，这里会显示电影信息"
        val busy = movie?.phase == UploadPhase.UPLOADING || movie?.phase == UploadPhase.PROCESSING
        view.findViewById<ProgressBar>(R.id.homeLessonProgress).apply {
            visibility = if (movie == null) View.GONE else View.VISIBLE
            isIndeterminate = busy
            progress = if (movie?.phase == UploadPhase.COMPLETED) 100 else 0
        }
        view.findViewById<TextView>(R.id.homeLessonProgressLabel).text = when (movie?.phase) {
            null -> ""
            UploadPhase.READY -> "待上传"
            UploadPhase.UPLOADING -> "上传中"
            UploadPhase.PROCESSING -> "分析中"
            UploadPhase.COMPLETED -> "查看闪卡"
            UploadPhase.SUCCEEDED -> "已上传"
            UploadPhase.FAILED, UploadPhase.PROCESS_FAILED -> "失败，点击重试"
            UploadPhase.INTERRUPTED -> "上传中断"
            UploadPhase.UNAVAILABLE -> "任务不可用"
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
