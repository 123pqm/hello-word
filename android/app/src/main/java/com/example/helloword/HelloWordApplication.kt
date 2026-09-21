package com.example.helloword

import android.app.Application
import com.example.helloword.api.RetrofitClient

class HelloWordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RetrofitClient.initialize(this)
    }
}
