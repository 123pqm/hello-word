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
    private val resolver = context.applicationContext.contentResolver
    private val preferences = context.applicationContext.getSharedPreferences("video_upload_sessions", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(readSaved())
    val state: StateFlow<UploadSession?> = mutableState
    private val pollingMutex = Mutex()

    private fun readSaved(): UploadSession? = try {
        preferences.getString(account, null)?.let {
            gson.fromJson(it, UploadSession::class.java)?.afterProcessRestart()
        }
    } catch (_: Exception) {
        null
    }

    private fun save(session: UploadSession?) {
        preferences.edit().apply {
            if (session == null) remove(account) else putString(account, gson.toJson(session))
        }.apply()
        mutableState.value = session
        LearningStore.syncSession(account, session)
    }

    fun select(session: UploadSession) {
        if (state.value?.isBusy != true) save(session)
    }

    fun clear() {
        if (state.value?.isBusy != true) save(null)
    }

    fun markFlashcardsOpened(movieId: Int) {
        val current = state.value ?: return
        if (current.phase == UploadPhase.COMPLETED && current.result?.movieId == movieId) {
            save(current.copy(flashcardsOpened = true))
        }
    }

    // The caller's STARTED lifecycle owns polling, not the application upload scope.
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
                val response = RetrofitClient.apiService.movieStatus(movieId)
                val body = response.body()
                response.errorBody()?.close()
                // Do not turn a missing route / task into a successful result.
                if (response.code() == 404) {
                    save(current.copy(phase = UploadPhase.UNAVAILABLE,
                        message = "无法找到视频任务或查询接口，请检查后端；可移除视频后重新选择"))
                    return
                }
                if (!response.isSuccessful || body?.code != 200 || body.data == null) {
                    throw IOException("查询失败（HTTP ${response.code()}）")
                }
                val latest = state.value ?: return
                if (latest.result?.movieId != movieId || latest.phase != UploadPhase.PROCESSING) return
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
                    if (vocabulary.mode == UploadVocabulary.SELECTED_CET4)
                        RequestBody.create(textType, vocabulary.wordIds.joinToString(",", "[", "]")) else null,
                    "Bearer $uploadToken"
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
        private val stores = mutableMapOf<String, VideoUploadStore>()

        // Called on the main thread. A returning Activity observes the existing job.
        fun forAccount(context: Context, account: String): VideoUploadStore {
            val store = stores.getOrPut(account) { VideoUploadStore(context.applicationContext, account) }
            if (RetrofitClient.account == account) LearningStore.activateAccount(account, store.state.value)
            return store
        }
    }
}
