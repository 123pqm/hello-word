package com.example.helloword.model

data class MovieUpload(
    val id: Int,
    val originalName: String,
    val fileSize: Long,
    val duration: Long,
    val status: String,
    val createdAt: String
)