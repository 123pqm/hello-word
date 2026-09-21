package com.example.helloword.api

import com.example.helloword.model.LoginRequest
import com.example.helloword.model.LoginResponse
import com.example.helloword.model.RegisterRequest
import com.example.helloword.model.RegisterResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import com.example.helloword.model.Cet4Response
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Multipart
import retrofit2.http.Part
import com.example.helloword.model.VideoUploadResponse
import com.example.helloword.model.MovieStatusResponse
import com.example.helloword.model.MovieWordsResponse
import retrofit2.http.Header
import retrofit2.http.Path
interface ApiService {
    @POST("user/checkin")
    suspend fun checkin(@Header("Authorization") authorization: String): Response<com.example.helloword.model.CheckinResponse>

    @retrofit2.http.Streaming
    @GET("video/{movie_id}/cover")
    suspend fun movieCover(@Path("movie_id") movieId: Int): Response<okhttp3.ResponseBody>
    @GET("video/history")
    suspend fun movieHistory(
        @Query("limit") limit: Int = 30,
        @Query("before_id") beforeId: Int? = null
    ): Response<com.example.helloword.model.MovieHistoryResponse>

    @GET("video/{movie_id}/status")
    suspend fun movieStatus(@Path("movie_id") movieId: Int): Response<MovieStatusResponse>

    @GET("video/{movie_id}/words")
    suspend fun movieWords(@Path("movie_id") movieId: Int): Response<MovieWordsResponse>

    @POST("user/sign")
    suspend fun register(
        @Body request: RegisterRequest
    ): RegisterResponse

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

    @Multipart
    @POST("video/upload")
    suspend fun uploadVideo(
        @Part file: MultipartBody.Part,
        @Part("selection_mode") selectionMode: RequestBody,
        @Part("selected_word_ids") selectedWordIds: RequestBody?,
        @Header("Authorization") authorization: String? = null
    ): Response<VideoUploadResponse>
}
