package com.example.helloword.api

import android.util.Log
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val BASE_URL = "http://172.20.10.2:8000/"

    // 演示：暂存在内存中，App 进程结束后会丢失
    @Volatile
    var token: String? = null
    var account: String? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->

                // ① 请求拦截：类似 axios.interceptors.request
                val builder = chain.request()
                    .newBuilder()
                    .header("Accept", "application/json")

                val currentToken = token
                if (!currentToken.isNullOrBlank()) {
                    // 请求头格式要和你的后端保持一致
                    builder.header(
                        "Authorization",
                        "Bearer $currentToken"
                    )
                }

                // ② 发送请求，获得 HTTP 响应
                val response = chain.proceed(builder.build())

                // ③ 响应拦截：类似 axios.interceptors.response
                when (response.code()) {
                    401 -> {
                        // 示例先记录日志；页面跳转需交给 UI 层处理
                        Log.w("Api", "登录状态无效，需要重新登录")
                    }

                    in 500..599 -> {
                        Log.w("Api", "服务器异常：${response.code()}")
                    }
                }

                // 交回 Retrofit，之后由 Gson 解析 JSON
                response
            }
            .build()
    }

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient) // 把带拦截器的客户端接进来
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
