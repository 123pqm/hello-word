package com.example.helloword

import android.os.Bundle
import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.helloword.model.UploadVocabulary

class select_book : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.book_select)

        findViewById<View>(R.id.backButton).setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.cet4Book).setOnClickListener {
            startActivity(Intent(this, Cet4Activity::class.java))
        }
        findViewById<View>(R.id.nextButton).setOnClickListener {
            startActivity(Intent(this, movie_upload::class.java).apply {
                putExtra(UploadVocabulary.EXTRA_SOURCE, UploadVocabulary.SOURCE_BOOK)
            })
        }
    }
}
