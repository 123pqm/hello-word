package com.example.helloword.api

import android.util.Log
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.example.helloword.MainActivity
import com.example.helloword.model.LoginResponse
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val BASE_URL = "http://172.20.10.2:8000/"

    private lateinit var preferences: SharedPreferences
    private lateinit var appContext: Context
    @Volatile
    var token: String? = null
    @Volatile
    var account: String? = null
    @Volatile
    var userId: Int? = null
    @Volatile
    private var expiresAt: Long = 0

    @Synchronized
    fun initialize(context: Context) {
        appContext = context.applicationContext
        preferences = context.applicationContext.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
        account = preferences.getString("account", null)
        userId = preferences.getInt("user_id", 0).takeIf { it > 0 }
        expiresAt = preferences.getLong("expires_at", 0)
        token = preferences.getString("access_token", null)
        if (!hasValidSession()) clearSession()
    }

    @Synchronized
    fun hasValidSession(): Boolean =
        !token.isNullOrBlank() && !account.isNullOrBlank() &&
            (userId ?: 0) > 0 && expiresAt > System.currentTimeMillis()

    @Synchronized
    fun saveSession(result: LoginResponse) {
        val id = requireNotNull(result.userId) { "登录响应缺少用户编号，请更新后端" }
        val value = requireNotNull(result.accessToken)
        val name = requireNotNull(result.account)
        val seconds = requireNotNull(result.expiresIn)
        require(id > 0 && value.isNotBlank() && name.isNotBlank() && seconds > 0)
        val expiry = System.currentTimeMillis() + seconds.toLong() * 1000
        check(preferences.edit().putInt("user_id", id).putString("account", name)
            .putString("access_token", value).putLong("expires_at", expiry).commit()) {
            "无法保存登录状态，请检查手机存储空间"
        }
        account = name
        userId = id
        expiresAt = expiry
        token = value
    }

    @Synchronized
    fun clearSession() {
        token = null
        account = null
        userId = null
        expiresAt = 0
        if (::preferences.isInitialized) preferences.edit().clear().apply()
    }

    @Synchronized
    private fun invalidateToken(value: String?) {
        // 旧请求返回 401 时不能清除刚切换的新账号。
        if (value != null && value == token) {
            clearSession()
            Handler(Looper.getMainLooper()).post {
                if (token == null) appContext.startActivity(Intent(appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->

                // ① 请求拦截：类似 axios.interceptors.request
                val builder = chain.request()
                    .newBuilder()
                    .header("Accept", "application/json")

                val currentToken = token
                if (!currentToken.isNullOrBlank() && chain.request().header("Authorization") == null) {
                    // 请求头格式要和你的后端保持一致
                    builder.header(
                        "Authorization",
                        "Bearer $currentToken"
                    )
                }

                // ② 发送请求，获得 HTTP 响应
                val response = chain.proceed(builder.build())
                if (response.code() == 401 && chain.request().url().encodedPath().startsWith("/video/")) {
                    invalidateToken(response.request().header("Authorization")?.removePrefix("Bearer "))
                }

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
