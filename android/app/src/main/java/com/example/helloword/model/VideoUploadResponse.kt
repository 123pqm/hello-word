package com.example.helloword.model

data class VideoUploadResponse(
    val code: Int,
    val message: String,
    val data: VideoData?
)

data class VideoData(
    val originalFilename: String,
    val filename: String,
    val path: String
)