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
interface ApiService {

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
        @Part("selected_word_ids") selectedWordIds: RequestBody?
    ): Response<VideoUploadResponse>
}
