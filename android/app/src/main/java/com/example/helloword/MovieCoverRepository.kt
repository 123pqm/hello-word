package com.example.helloword

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.helloword.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object MovieCoverRepository {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val requests = Semaphore(3)

    suspend fun load(movieId: Int, owner: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (RetrofitClient.userId != owner) return@withContext null
        val key = "$owner/$movieId"
        cache.get(key)?.let { return@withContext it }
        requests.withPermit {
            if (RetrofitClient.userId != owner) return@withPermit null
            val response = RetrofitClient.apiService.movieCover(movieId)
            response.errorBody()?.close()
            val bitmap = response.body()?.use { body ->
                if (response.isSuccessful) BitmapFactory.decodeStream(body.byteStream()) else null
            }
            if (RetrofitClient.userId != owner) return@withPermit null
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        }
    }
}
