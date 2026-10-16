package com.example.helloword.model

data class Cet4Response(val data: List<Cet4Word>?, val total: Int? = null)

data class Cet4Word(
    val id: Int,
    val word: String,
    val meaning: String,
    val pos: String?,
    val frequency: Int? = null
)


data class WordSearchResponse(
    val data: List<Cet4Word>,
    @com.google.gson.annotations.SerializedName("next_offset") val nextOffset: Int?,
    @com.google.gson.annotations.SerializedName("has_more") val hasMore: Boolean
)
