package com.example.helloword.model

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    val account: String?,
    val reply: String?,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("expires_in") val expiresIn: Int?
)
