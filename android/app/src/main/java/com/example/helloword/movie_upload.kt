package com.example.helloword

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.UploadPhase
import com.example.helloword.model.UploadSession
import com.example.helloword.model.UploadVocabulary
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

class movie_upload : AppCompatActivity() {
    private lateinit var uploadMovieCard: LinearLayout
    private lateinit var movieCover: ImageView
    private lateinit var movieName: TextView
    private lateinit var movieSize: TextView
    private lateinit var movieDuration: TextView
    private lateinit var btnRemoveMovie: TextView
    private lateinit var confirmButton: TextView
    private lateinit var uploadStatus: TextView
    private lateinit var store: VideoUploadStore
    private lateinit var incomingVocabulary: UploadVocabulary
    private var renderedUri: String? = null
    private var openingFlashcards = false

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && ::store.isInitialized) {
            try {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    Toast.makeText(this, "此文件不支持长期访问，重启后可能需要重新选择", Toast.LENGTH_LONG).show()
                }
                val (name, size) = getFileInfo(uri)
                store.select(UploadSession(uri.toString(), name, size, getVideoDuration(uri), incomingVocabulary))
            } catch (_: Exception) {
                Toast.makeText(this, "无法读取视频，请重新选择", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.upload_movie)
        val account = RetrofitClient.account
        if (account.isNullOrBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        store = VideoUploadStore.forAccount(applicationContext, account)
        incomingVocabulary = try {
            UploadVocabulary.fromParent(
                intent.getStringExtra(UploadVocabulary.EXTRA_SOURCE),
                intent.getIntArrayExtra(UploadVocabulary.EXTRA_WORD_IDS)
            )
        } catch (error: IllegalArgumentException) {
            store.state.value?.vocabulary ?: run {
                Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        uploadMovieCard = findViewById(R.id.uploadMovieCard)
        movieCover = findViewById(R.id.movieCover)
        movieName = findViewById(R.id.movieName)
        movieSize = findViewById(R.id.movieSize)
        movieDuration = findViewById(R.id.movieDuration)
        btnRemoveMovie = findViewById(R.id.btnRemoveMovie)
        confirmButton = findViewById(R.id.confirmButton)
        uploadStatus = findViewById(R.id.uploadStatus)
        uploadMovieCard.setOnClickListener {
            if (store.state.value?.isBusy != true) videoPicker.launch(arrayOf("video/*"))
        }
        btnRemoveMovie.setOnClickListener { store.clear() }
        confirmButton.setOnClickListener {
            val session = store.state.value
            if (session?.phase == UploadPhase.COMPLETED) {
                lifecycleScope.launch { openFlashcards(session) }
            } else store.upload()
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        render(store.state.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { store.observeProcessing() }
                store.state.collect { render(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                store.state.map { session ->
                    session?.result?.movieId?.takeIf {
                        session.phase == UploadPhase.COMPLETED && !session.flashcardsOpened
                    }
                }.distinctUntilChanged().collectLatest { movieId ->
                    val session = store.state.value
                    if (movieId != null && session != null) openFlashcards(session)
                }
            }
        }
    }

    private suspend fun openFlashcards(session: UploadSession) {
        val movieId = session.result?.movieId ?: return
        if (openingFlashcards) return
        openingFlashcards = true
        confirmButton.isEnabled = false
        uploadStatus.text = "处理完成，正在加载单词闪卡…"
        try {
            MovieWordsRepository.load(applicationContext, movieId)
            // The user might have selected a different video while the request was in flight.
            if (store.state.value?.result?.movieId != movieId || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            startActivity(Intent(this, WordFlashcardActivity::class.java).apply {
                putExtra(WordFlashcardActivity.EXTRA_MOVIE_ID, movieId)
                putExtra(WordFlashcardActivity.EXTRA_VIDEO_URI, session.uri)
            })
            store.markFlashcardsOpened(movieId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            uploadStatus.text = error.localizedMessage ?: "单词加载失败，请点击查看闪卡重试"
        } finally {
            openingFlashcards = false
            confirmButton.isEnabled = store.state.value?.phase == UploadPhase.COMPLETED || store.state.value?.canUpload == true
        }
    }

    private fun render(session: UploadSession?) {
        val uploading = session?.isBusy == true
        uploadMovieCard.isEnabled = !uploading
        btnRemoveMovie.isEnabled = !uploading
        btnRemoveMovie.visibility = if (session == null || uploading) View.GONE else View.VISIBLE
        confirmButton.visibility = if (session?.showConfirm == true) View.VISIBLE else View.GONE
        confirmButton.isEnabled = !openingFlashcards && (session?.canUpload == true || session?.phase == UploadPhase.COMPLETED)
        confirmButton.text = when (session?.phase) {
            UploadPhase.SUCCEEDED -> "已上传"
            UploadPhase.COMPLETED -> "查看单词闪卡"
            UploadPhase.UNAVAILABLE -> "任务不可用"
            UploadPhase.PROCESS_FAILED -> "重新上传处理"
            UploadPhase.FAILED, UploadPhase.INTERRUPTED -> "重试上传"
            else -> "确认"
        }
        uploadStatus.text = session?.message ?: "请选择视频，确认后开始上传"
        findViewById<TextView>(R.id.uploadVocabularySummary).text =
            (session?.vocabulary ?: incomingVocabulary).summary
        findViewById<TextView>(R.id.uploadScopeHint).apply {
            visibility = if (session != null && session.vocabulary != incomingVocabulary) View.VISIBLE else View.GONE
            text = "已恢复上次视频及其匹配范围；如需使用本次选择的词库，请移除视频后重新选择"
        }
        movieName.text = session?.name ?: "点击选择电影"
        movieSize.text = session?.let { formatFileSize(it.size) } ?: "支持 MP4、MOV、MKV 等视频"
        movieDuration.text = session?.duration ?: "点击此处上传本地视频"
        if (renderedUri != session?.uri) {
            renderedUri = session?.uri
            movieCover.setImageResource(android.R.drawable.ic_menu_upload)
            session?.let { loadVideoThumbnail(Uri.parse(it.uri)) }
        }
    }

    private fun getFileInfo(uri: Uri): Pair<String, Long> {

        var fileName = "未知视频"

        var fileSize = 0L

        contentResolver.query(
            uri,
            null,
            null,
            null,
            null
        )?.use { cursor ->

            val nameIndex =
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

            val sizeIndex =
                cursor.getColumnIndex(OpenableColumns.SIZE)

            if (cursor.moveToFirst()) {

                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }

                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }
        }

        return Pair(fileName, fileSize)
    }


    /**
     * 格式化文件大小
     *
     * 例如：
     * 2097152000
     *
     * ->
     *
     * 1.95 GB
     */
    private fun formatFileSize(bytes: Long): String {

        if (bytes <= 0) {
            return "未知大小"
        }

        val kb = bytes / 1024.0

        val mb = kb / 1024.0

        val gb = mb / 1024.0

        return when {

            gb >= 1 -> {
                String.format(
                    Locale.getDefault(),
                    "%.2f GB",
                    gb
                )
            }

            mb >= 1 -> {
                String.format(
                    Locale.getDefault(),
                    "%.2f MB",
                    mb
                )
            }

            else -> {
                String.format(
                    Locale.getDefault(),
                    "%.2f KB",
                    kb
                )
            }
        }
    }


    /**
     * 获取视频时长
     */
    private fun getVideoDuration(uri: Uri): String {

        return try {

            val retriever = MediaMetadataRetriever()

            retriever.setDataSource(
                this,
                uri
            )

            val durationString =
                retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )

            val durationMs =
                durationString?.toLongOrNull() ?: 0L

            retriever.release()

            formatDuration(durationMs)

        } catch (e: Exception) {

            "--:--:--"
        }
    }


    /**
     * 毫秒转换成：
     *
     * 02:22:34
     */
    private fun formatDuration(durationMs: Long): String {

        val totalSeconds =
            durationMs / 1000

        val hours =
            totalSeconds / 3600

        val minutes =
            (totalSeconds % 3600) / 60

        val seconds =
            totalSeconds % 60

        return String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }


    /**
     * 获取视频第一帧作为封面
     */
    private fun loadVideoThumbnail(uri: Uri) {

        try {

            val retriever =
                MediaMetadataRetriever()

            retriever.setDataSource(
                this,
                uri
            )

            val bitmap =
                retriever.getFrameAtTime(
                    1_000_000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

            if (bitmap != null) {
                movieCover.setImageBitmap(bitmap)
            }

            retriever.release()

        } catch (e: Exception) {

            e.printStackTrace()
        }
    }



}
