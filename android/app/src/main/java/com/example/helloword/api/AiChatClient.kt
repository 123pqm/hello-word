package com.example.helloword.api

import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException

data class AiChatMessage(val role: String, val content: String)
data class AiChatRequest(val messages: List<AiChatMessage>, val word: String = "", val sentence: String = "")

/** SSE 每个事件以空行结束，JSON 中的换行由 Gson 解码。 */
object AiChatStream {
    private data class Event(val type: String?, val content: String?, val message: String?)

    fun read(reader: BufferedReader, onDelta: (String) -> Unit) {
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: throw IOException("连接已中断，请重新提问")
            if (line.isEmpty() && data.isNotEmpty()) {
                val event = try { Gson().fromJson(data.toString(), Event::class.java) }
                    catch (_: Exception) { throw IOException("AI 返回格式异常，请重试") }
                data.setLength(0)
                when (event?.type) {
                    "delta" -> onDelta(event.content ?: throw IOException("AI 返回格式异常"))
                    "done" -> return
                    "error" -> throw IOException(event.message ?: "AI 服务暂时不可用")
                    else -> throw IOException("AI 返回格式异常，请重试")
                }
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
    }
}

object AiChatClient {
    fun start(request: AiChatRequest, onDelta: (String) -> Unit,
              onDone: () -> Unit, onError: (String) -> Unit): Call {
        val call = RetrofitClient.newChatCall(Gson().toJson(request))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) onError("无法连接或连接超时，请检查网络和后端服务")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw IOException(when (it.code()) {
                            422 -> "问题或对话过长，请缩短问题或开始新对话"
                            503 -> "AI 服务尚未配置或暂时不可用"
                            else -> "请求失败（${it.code()}），请稍后重试"
                        })
                        if (it.header("Content-Type")?.startsWith("text/event-stream") != true)
                            throw IOException("后端未返回流式内容，请更新并重启后端")
                        val body = it.body() ?: throw IOException("AI 返回为空")
                        AiChatStream.read(body.charStream().buffered(), onDelta)
                    }
                    if (!call.isCanceled()) onDone()
                } catch (e: IOException) {
                    if (!call.isCanceled()) onError(e.message ?: "连接已中断，请重新提问")
                }
            }
        })
        return call
    }
}
