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

        view.findViewById<View>(R.id.homeNewTask).setOnClickListener {
            startActivity(Intent(requireContext(), select_book::class.java))
        }
        // Hydrate the global state from the existing account-scoped persistent upload session.
        RetrofitClient.account?.let { VideoUploadStore.forAccount(requireContext(), it) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
}
