package com.example.helloword

import android.os.Bundle
import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.helloword.model.UploadVocabulary
import com.example.helloword.model.Wordbooks
import android.widget.TextView
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class select_book : AppCompatActivity() {
    private var selectedBookId = Wordbooks.CET4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.book_select)
        selectedBookId = savedInstanceState?.getInt("selected_book", 1) ?: 1
        val root = findViewById<View>(R.id.bookSelectRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        findViewById<View>(R.id.backButton).setOnClickListener {
            finish()
        }
        mapOf(R.id.cet4Book to 1, R.id.graduateBook to 3, R.id.graduateHighBook to 4).forEach { (viewId, bookId) ->
            findViewById<View>(viewId).apply {
                WordbookCover.applyTo(findViewById(R.id.myBookCover), bookId)
                findViewById<TextView>(R.id.myBookName).text = Wordbooks.name(bookId)
                findViewById<TextView>(R.id.myBookDescription).text = when (bookId) {
                    3 -> "5530 词条 · 2024 考纲词表"
                    4 -> "2444 词条 · 混合考试语料词频 ≥ 40"
                    else -> "大学英语四级词库"
                }
                findViewById<TextView>(R.id.myBookCoverLabel).text = when (bookId) { 3 -> "考\n研"; 4 -> "词\n频"; else -> "CET\n4" }
                setOnClickListener {
                    selectedBookId = bookId
                    updateSelected()
                    startActivity(Intent(this@select_book, Cet4Activity::class.java).apply {
                        putExtra(UploadVocabulary.EXTRA_BOOK_ID, bookId)
                    })
                }
            }
        }
        updateSelected()
        findViewById<View>(R.id.nextButton).setOnClickListener {
            startActivity(Intent(this, movie_upload::class.java).apply {
                putExtra(UploadVocabulary.EXTRA_SOURCE, UploadVocabulary.SOURCE_BOOK)
                putExtra(UploadVocabulary.EXTRA_BOOK_ID, selectedBookId)
            })
        }
    }

    private fun updateSelected() {
        mapOf(R.id.cet4Book to 1, R.id.graduateBook to 3, R.id.graduateHighBook to 4).forEach { (viewId, bookId) ->
            findViewById<View>(viewId).apply {
                findViewById<TextView>(R.id.myBookStatus).text = if (bookId == selectedBookId) "已选" else "查看 / 选择"
                findViewById<ImageView>(R.id.myBookSelected).setImageResource(
                    if (bookId == selectedBookId) R.drawable.vh_check else R.drawable.vh_unchecked)
            }
        }
        findViewById<TextView>(R.id.nextButton).text = "使用整本${Wordbooks.name(selectedBookId)}"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_book", selectedBookId)
        super.onSaveInstanceState(outState)
    }
}
