package com.example.helloword

import com.example.helloword.api.ApiService
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.MovieWords
import com.example.helloword.model.MovieWordsResponse
import retrofit2.Response

internal fun movieApi(words: MovieWords): ApiService = object : ApiService by RetrofitClient.apiService {
    override suspend fun movieWords(movieId: Int): Response<MovieWordsResponse> {
        check(movieId == words.movieId)
        return Response.success(MovieWordsResponse(200, words))
    }
}
