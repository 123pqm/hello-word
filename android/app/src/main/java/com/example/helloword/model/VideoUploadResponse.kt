package com.example.helloword.model

import com.google.gson.annotations.SerializedName

data class VideoUploadResponse(
    val code: Int,
    val message: String,
    val data: VideoData?
)

data class VideoData(
    val originalFilename: String,
    val filename: String,
    val path: String,
    @SerializedName("movie_id") val movieId: Int? = null,
    val status: String? = null
)

data class MovieStatusResponse(val code: Int, val data: MovieStatus?)

data class MovieStatus(
    @SerializedName("movie_id") val movieId: Int,
    val status: String?,
    @SerializedName("error_message") val errorMessage: String?,
    @SerializedName("matched_word_count") val matchedWordCount: Int
)
