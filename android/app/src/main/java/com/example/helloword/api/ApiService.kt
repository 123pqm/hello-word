package com.example.helloword.api

import com.example.helloword.model.LoginRequest
import com.example.helloword.model.LoginResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import com.example.helloword.model.Cet4Response


interface ApiService {

    @POST("user/login")
    suspend fun login(
        @Body request: LoginRequest
    ): LoginResponse

    @GET(value = "user/chose/cet4")
    suspend fun chose_words(
        @Query("start_index") startIndex: Int,
        @Query("end_index") endIndex: Int,
        @Query("book_id") bookId: Int
    ): Cet4Response
}
