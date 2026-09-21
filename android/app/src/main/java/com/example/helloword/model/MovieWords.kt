package com.example.helloword.model

import com.google.gson.annotations.SerializedName

data class MovieWord(
    val word: String,
    val meaning: String,
    @SerializedName("start_time") val startTime: Double,
    @SerializedName("end_time") val endTime: Double,
    @SerializedName("sentence_text") val sentenceText: String? = null
)

data class MovieWords(
    @SerializedName("movie_id") val movieId: Int,
    val words: List<MovieWord>
)

data class MovieWordsResponse(val code: Int, val data: MovieWords?)
