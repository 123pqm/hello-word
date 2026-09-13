package com.example.helloword.model

import android.accounts.Account

data class LoginRequest(
    val account:  String,
    val password: String
)
