package com.example.helloword.model

data class Cet4Response(val data: List<Cet4Word>?)

data class Cet4Word(
    val id: Int,
    val word: String,
    val meaning: String,
    val pos: String?
)
