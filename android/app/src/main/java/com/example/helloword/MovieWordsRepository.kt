package com.example.helloword

import android.content.Context
import android.util.AtomicFile
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.MovieWords
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Keep the response on disk rather than putting a potentially large word list in an Intent. */
object MovieWordsRepository {
    private val gson = Gson()
    internal var apiService = RetrofitClient.apiService

    suspend fun load(context: Context, movieId: Int): MovieWords = withContext(Dispatchers.IO) {
        require(movieId > 0)
        val userId = RetrofitClient.userId ?: throw IOException("请重新登录")
        val account = RetrofitClient.account ?: throw IOException("请重新登录")
        val directory = File(context.applicationContext.filesDir, "movie_flashcards/user_$userId").apply { mkdirs() }
        val cache = AtomicFile(File(directory, "$movieId.json"))
        // 每次由后端校验归属，不读取旧的、没有用户归属的缓存。
        val response = apiService.movieWords(movieId)
        val body = response.body()
        response.errorBody()?.close()
        if (!response.isSuccessful || body?.code != 200 || body.data == null) {
            throw IOException(when (response.code()) {
                401 -> "登录已失效，请重新登录"
                404 -> "没有找到该视频的匹配结果"
                409 -> "视频还没有处理成功，请返回上传页查看状态"
                else -> "单词加载失败，请检查网络后重试"
            })
        }
        if (RetrofitClient.userId != userId || RetrofitClient.account != account) {
            throw IOException("账号已切换，请重新打开闪卡")
        }
        val data = body.data
        if (data.movieId != movieId || data.words == null) throw IOException("返回的单词数据不完整")
        val output = cache.startWrite()
        try {
            output.write(gson.toJson(data).toByteArray(Charsets.UTF_8))
            cache.finishWrite(output)
        } catch (error: Exception) {
            cache.failWrite(output)
            throw IOException("无法保存单词结果，请检查手机存储空间", error)
        }
        data
    }
}
