package com.example.helloword

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.helloword.api.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.Okio
import java.io.IOException
import android.util.Log
import com.example.helloword.model.UploadVocabulary


class movie_upload : AppCompatActivity() {

    private lateinit var uploadMovieCard: LinearLayout

    private lateinit var movieCover: ImageView
    private lateinit var movieName: TextView
    private lateinit var movieSize: TextView
    private lateinit var movieDuration: TextView
    private lateinit var btnRemoveMovie: TextView

    private lateinit var confirmButton: TextView
    // 当前用户选择的视频
    private var selectedVideoUri: Uri? = null
    private var isUploading = false
    private lateinit var vocabulary: UploadVocabulary
    /**
     * Android 官方文件选择器
     */
    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->

            if (uri != null) {

                selectedVideoUri = uri

                // 尝试保留文件读取权限
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }

                showSelectedVideo(uri)
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.upload_movie)
        vocabulary = try {
            UploadVocabulary.fromParent(
                intent.getStringExtra(UploadVocabulary.EXTRA_SOURCE),
                intent.getIntArrayExtra(UploadVocabulary.EXTRA_WORD_IDS)
            )
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.uploadVocabularySummary).text = vocabulary.summary
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }
        initView()

        initClick()
    }
    private fun initView() {

        uploadMovieCard = findViewById(R.id.uploadMovieCard)

        movieCover = findViewById(R.id.movieCover)

        movieName = findViewById(R.id.movieName)

        movieSize = findViewById(R.id.movieSize)

        movieDuration = findViewById(R.id.movieDuration)

        btnRemoveMovie = findViewById(R.id.btnRemoveMovie)

        confirmButton = findViewById(R.id.confirmButton)
        confirmButton.visibility = View.GONE
    }


    private fun initClick() {

        // 点击整个卡片上传电影
        uploadMovieCard.setOnClickListener {
            if (isUploading) return@setOnClickListener

            // video/* 表示只允许选择视频文件
            videoPicker.launch(
                arrayOf("video/*")
            )
        }


        // 删除当前选择的视频
        btnRemoveMovie.setOnClickListener {
            if (isUploading) return@setOnClickListener

            // 防止事件继续传递到外面的上传卡片
            removeSelectedMovie()
        }

        confirmButton.setOnClickListener {
            uploadSelectedVideo()
        }


        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }


    private fun uploadSelectedVideo() {
        val uri = selectedVideoUri ?: return
        if (isUploading) return

        setUploading(true)
        lifecycleScope.launch {
            try {
                val filePart = withContext(Dispatchers.IO) {
                    val (fileName, fileSize) = getFileInfo(uri)
                    val mediaType = MediaType.parse(
                        contentResolver.getType(uri) ?: "application/octet-stream"
                    )
                    val requestBody = object : RequestBody() {
                        override fun contentType(): MediaType? = mediaType

                        override fun contentLength(): Long =
                            if (fileSize > 0) fileSize else -1L

                        override fun writeTo(sink: BufferedSink) {
                            val input = contentResolver.openInputStream(uri)
                                ?: throw IOException("无法读取所选视频")
                            Okio.source(input).use { source ->
                                sink.writeAll(source)
                            }
                        }
                    }
                    MultipartBody.Part.createFormData("file", fileName, requestBody)
                }
                val textType = MediaType.parse("text/plain; charset=utf-8")
                val modePart = RequestBody.create(textType, vocabulary.mode)
                val idsPart = if (vocabulary.mode == UploadVocabulary.SELECTED_CET4) {
                    RequestBody.create(textType, vocabulary.wordIds.joinToString(",", "[", "]"))
                } else null
                val response = RetrofitClient.apiService.uploadVideo(filePart, modePart, idsPart)

                Log.d("VideoUpload", "状态码：${response.code()}")

                if (response.isSuccessful) {
                    Log.d("VideoUpload", "返回数据：${response.body()}")
                } else {
                    Log.e("VideoUpload", "错误内容：${response.errorBody()?.string()}")
                }

                val message = if (response.isSuccessful) {
                    "上传成功"
                } else {
                    response.errorBody()?.close()
                    "上传失败：${response.code()}"
                }
                Toast.makeText(this@movie_upload, message, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@movie_upload,
                    "上传失败：${e.localizedMessage ?: "请稍后重试"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setUploading(false)
            }
        }
    }

    private fun setUploading(uploading: Boolean) {
        isUploading = uploading
        confirmButton.isEnabled = !uploading
        uploadMovieCard.isEnabled = !uploading
        btnRemoveMovie.isEnabled = !uploading
        confirmButton.text = if (uploading) "上传中…" else "确定"
        confirmButton.visibility = if (selectedVideoUri != null) View.VISIBLE else View.GONE
    }

    /**
     * 用户选择电影之后更新 UI
     */
    private fun showSelectedVideo(uri: Uri) {

        val fileInfo = getFileInfo(uri)

        movieName.text = fileInfo.first

        movieSize.text = formatFileSize(
            fileInfo.second
        )

        movieDuration.text = getVideoDuration(uri)

        btnRemoveMovie.visibility = TextView.VISIBLE

        confirmButton.visibility= TextView.VISIBLE

        // 获取视频第一帧当封面
        loadVideoThumbnail(uri)
    }


    /**
     * 获取文件名和文件大小
     */
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


    /**
     * 移除当前视频
     */
    private fun removeSelectedMovie() {

        selectedVideoUri = null

        movieName.text =
            "点击选择电影"

        movieSize.text =
            "支持 MP4、MOV、MKV 等视频"

        movieDuration.text =
            "点击此处上传本地视频"

        movieCover.setImageResource(
            android.R.drawable.ic_menu_upload
        )

        btnRemoveMovie.visibility =
            TextView.GONE
        confirmButton.visibility=
            TextView.GONE
    }





}
