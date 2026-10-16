package com.example.helloword

import android.content.Context
import android.net.Uri

/** 按账号和电影保存原文件地址，不随最新上传草稿的替换或清除而丢失。 */
class LocalMovieFiles(context: Context, private val account: String) {
    private val preferences = context.applicationContext.getSharedPreferences("local_movie_files", Context.MODE_PRIVATE)

    fun remember(movieId: Int, uri: Uri) {
        if (movieId > 0 && uri.scheme in setOf("content", "file")) {
            preferences.edit().putString("$account:$movieId", uri.toString()).apply()
        }
    }

    fun find(movieId: Int): Uri? = preferences.getString("$account:$movieId", null)?.let(Uri::parse)

    fun remove(movieId: Int) { preferences.edit().remove("$account:$movieId").apply() }
}
