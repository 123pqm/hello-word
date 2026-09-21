package com.example.helloword

import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.CheckinData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CheckinState(val userId: Int? = null, val data: CheckinData? = null, val error: String? = null)

/** 服务器保存正式记录；内存状态只供当前账号首页显示。 */
object CheckinStore {
    private val mutableState = MutableStateFlow(CheckinState())
    val state = mutableState.asStateFlow()
    internal var apiService = RetrofitClient.apiService

    suspend fun refresh(): Long {
        val owner = RetrofitClient.userId
        val token = RetrofitClient.token
        mutableState.value = CheckinState(owner)
        if (owner == null || token.isNullOrBlank()) {
            mutableState.value = CheckinState(owner, error = "请登录后查看打卡")
            return 60
        }
        try {
            // 固定本次请求的账号凭证，避免切换账号时写入另一人的打卡。
            val response = apiService.checkin("Bearer $token")
            val body = response.body()
            response.errorBody()?.close()
            if (owner != RetrofitClient.userId || token != RetrofitClient.token) return 60
            if (!response.isSuccessful || body?.code != 200 || body.data == null) {
                mutableState.value = CheckinState(owner, error = if (response.code() == 401)
                    "登录已过期，请重新登录" else "打卡暂时失败，稍后自动重试")
                return 60
            }
            val data = body.data
            require(data.userId == owner && data.days.size == 7)
            mutableState.value = CheckinState(owner, data)
            // 使用后端给出的剩余秒数，不依赖手机的日期设置。
            return (data.nextCheckinAfterSeconds + 1).coerceIn(1, 86401)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (owner == RetrofitClient.userId && token == RetrofitClient.token) {
                mutableState.value = CheckinState(owner, error = "打卡暂时失败，请检查网络，稍后自动重试")
            }
            return 60
        }
    }
}
