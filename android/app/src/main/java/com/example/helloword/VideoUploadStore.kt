package com.example.helloword

import android.content.Context
import android.net.Uri
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.UploadPhase
import com.example.helloword.model.UploadSession
import com.example.helloword.model.UploadVocabulary
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.Okio
import java.io.IOException

/** One retained upload per account. Never holds an Activity or a View. */
class VideoUploadStore private constructor(context: Context, private val account: String) {
    private val appContext = context.applicationContext
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences("video_upload_sessions", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val localMovies = LocalMovieFiles(context, account)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(readSaved())
    val state: StateFlow<UploadSession?> = mutableState
    private val pollingMutex = Mutex()

    init {
        state.value?.let { session ->
            session.result?.movieId?.let {
                if (localMovies.find(it) == null) localMovies.remember(it, Uri.parse(session.uri))
            }
        }
    }

    private fun readSaved(): UploadSession? = try {
        preferences.getString(account, null)?.let {
            gson.fromJson(it, UploadSession::class.java)?.afterProcessRestart()
        }
    } catch (_: Exception) {
        null
    }

    private fun save(session: UploadSession?) {
        session?.result?.movieId?.let {
            if (localMovies.find(it) == null) localMovies.remember(it, Uri.parse(session.uri))
        }
        preferences.edit().apply {
            if (session == null) remove(account) else putString(account, gson.toJson(session))
        }.apply()
        mutableState.value = session
        LearningStore.syncSession(account, session)
    }

    fun select(session: UploadSession) {
        if (state.value?.isBusy != true) save(session)
    }

    fun updateDraftVocabulary(vocabulary: UploadVocabulary) {
        val draft = state.value ?: return
        // 只修改尚未提交的草稿；已提交或上传结果不确定的任务保留原匹配范围。
        if (RetrofitClient.account == account && draft.phase == UploadPhase.READY &&
            draft.result == null && draft.vocabulary != vocabulary) {
            save(draft.copy(vocabulary = vocabulary))
        }
    }

    fun clear() {
        if (state.value?.isBusy != true) save(null)
    }

    fun forgetDeletedMovie(movieId: Int) {
        if (account == RetrofitClient.account) RetrofitClient.userId?.let {
            VideoNotifications.cancelMovie(appContext, it, movieId)
        }
        if (state.value?.result?.movieId == movieId) save(null)
        localMovies.remove(movieId)
        LearningStore.releaseNavigation(account, movieId)
    }

    fun markFlashcardsOpened(movieId: Int) {
        val current = state.value ?: return
        if (current.phase == UploadPhase.COMPLETED && current.result?.movieId == movieId) {
            save(current.copy(flashcardsOpened = true))
        }
    }

    // Foreground UI and the notification service share one poller through the mutex.
    suspend fun observeProcessing() {
        state.map { session ->
            session?.result?.movieId?.takeIf { session.phase == UploadPhase.PROCESSING }
        }.distinctUntilChanged().collectLatest { movieId ->
            if (movieId != null) pollingMutex.withLock { poll(movieId) }
        }
    }

    private suspend fun poll(movieId: Int) {
        var failures = 0
        while (true) {
            val current = state.value ?: return
            if (current.result?.movieId != movieId || current.phase != UploadPhase.PROCESSING) return
            if (RetrofitClient.account != account) return
            try {
                val trackingToken = current.result?.trackingToken
                val response = if (trackingToken.isNullOrBlank()) statusApi.movieStatus(movieId)
                    else statusApi.movieProgress(movieId, trackingToken)
                val body = response.body()
                response.errorBody()?.close()
                val latest = state.value ?: return
                if (latest.result?.movieId != movieId || latest.phase != UploadPhase.PROCESSING) return
                if (RetrofitClient.account != account) return
                if (response.code() == 401) {
                    save(latest.copy(phase = UploadPhase.UNAVAILABLE,
                        message = "提醒凭证已失效，请重新登录后到我的观影查看结果"))
                    return
                }
                // Do not turn a missing route / task into a successful result.
                if (response.code() == 404) {
                    save(latest.copy(phase = UploadPhase.UNAVAILABLE,
                        message = "无法找到视频任务或查询接口，请检查后端；可移除视频后重新选择"))
                    return
                }
                if (!response.isSuccessful || body?.code != 200 || body.data == null) {
                    throw IOException("查询失败（HTTP ${response.code()}）")
                }
                val updated = latest.withServerStatus(body.data)
                save(updated)
                failures = 0
                if (updated.phase != UploadPhase.PROCESSING) return
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failures = (failures + 1).coerceAtMost(5)
                val latest = state.value ?: return
                if (latest.result?.movieId != movieId || latest.phase != UploadPhase.PROCESSING) return
                save(latest.copy(message = "暂时无法查询处理进度，正在自动重试…"))
            }
            delay(if (failures == 0) 3_000L else failures * 3_000L)
        }
    }

    fun upload() {
        val session = state.value ?: return
        if (!session.canUpload) return
        if (RetrofitClient.account != account || RetrofitClient.token.isNullOrBlank()) {
            save(session.copy(phase = UploadPhase.FAILED, message = "请重新登录后上传"))
            return
        }
        val uploadToken = RetrofitClient.token ?: return
        save(session.copy(phase = UploadPhase.UPLOADING, message = "正在上传，可离开此页面，返回后查看结果"))
        if (!VideoProcessingService.start(appContext)) {
            android.widget.Toast.makeText(appContext, "后台提醒暂时无法启动，请返回应用查看处理结果", android.widget.Toast.LENGTH_LONG).show()
        }
        // Application-owned work continues when the upload Activity is finished.
        scope.launch {
            try {
                val uri = Uri.parse(session.uri)
                val mediaType = MediaType.parse(resolver.getType(uri) ?: "application/octet-stream")
                val body = object : RequestBody() {
                    override fun contentType(): MediaType? = mediaType
                    override fun contentLength(): Long = if (session.size > 0) session.size else -1L
                    override fun writeTo(sink: BufferedSink) {
                        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取视频，请重新选择")
                        Okio.source(input).use { sink.writeAll(it) }
                    }
                }
                val textType = MediaType.parse("text/plain; charset=utf-8")
                val vocabulary = session.vocabulary
                val response = RetrofitClient.apiService.uploadVideo(
                    MultipartBody.Part.createFormData("file", session.name, body),
                    RequestBody.create(textType, vocabulary.mode),
                    if (vocabulary.isSelected)
                        RequestBody.create(textType, vocabulary.wordIds.joinToString(",", "[", "]")) else null,
                    "Bearer $uploadToken",
                    RequestBody.create(textType, vocabulary.effectiveBookId.toString())
                )
                val result = response.body()
                response.errorBody()?.close()
                if (response.isSuccessful && result?.code == 200 && result.data != null) {
                    val uploaded = session.copy(result = result.data)
                    val movieId = result.data.movieId
                    if (movieId != null && movieId > 0) {
                        // Always verify through the status endpoint, including fast-completing jobs.
                        save(uploaded.copy(phase = UploadPhase.PROCESSING, message = "上传成功，正在查询处理进度…"))
                    } else {
                        save(uploaded.copy(phase = UploadPhase.SUCCEEDED,
                            message = "上传成功，但后端未返回任务编号，无法查询处理进度"))
                    }
                } else {
                    save(session.copy(phase = UploadPhase.FAILED, message = "上传未成功（HTTP ${response.code()}），请重试"))
                }
            } catch (error: CancellationException) {
                save(session.copy(phase = UploadPhase.INTERRUPTED, message = "上传中断，无法确认服务器结果；重试可能重复上传"))
                throw error
            } catch (error: Exception) {
                save(session.copy(phase = UploadPhase.FAILED,
                    message = "未能确认上传结果：${error.localizedMessage ?: "请检查网络或重新选择视频"}；重试可能重复上传"))
            }
        }
    }

    companion object {
        internal var statusApi = RetrofitClient.apiService
        private val stores = mutableMapOf<String, VideoUploadStore>()

        // Called on the main thread. A returning Activity observes the existing job.
        fun forAccount(context: Context, account: String): VideoUploadStore {
            val store = stores.getOrPut(account) { VideoUploadStore(context.applicationContext, account) }
            if (RetrofitClient.account == account) LearningStore.activateAccount(account, store.state.value)
            return store
        }
    }
}
