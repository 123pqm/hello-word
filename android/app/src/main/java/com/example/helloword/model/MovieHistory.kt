package com.example.helloword.model

import com.google.gson.annotations.SerializedName

data class MovieHistoryItem(
    val id: Int,
    @SerializedName("file_name") val fileName: String?,
    val status: String?,
    @SerializedName("created_at") val createdAt: String?
)
data class MovieHistoryPage(
    val items: List<MovieHistoryItem>,
    @SerializedName("next_before_id") val nextBeforeId: Int?
)
data class MovieHistoryResponse(val code: Int, val data: MovieHistoryPage?)
