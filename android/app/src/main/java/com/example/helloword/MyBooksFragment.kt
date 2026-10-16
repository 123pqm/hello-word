package com.example.helloword

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.helloword.model.UploadVocabulary
import com.example.helloword.model.Wordbooks

/** 底部导航中的词书管理页，与新建视频任务的选书流程独立。 */
class MyBooksFragment : Fragment(R.layout.fragment_my_books) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindBook(view.findViewById(R.id.myBookCet4), "CET-4", "适合大学英语四级备考", "CET\n4",
            available = true)
        bindBook(view.findViewById(R.id.myBookCet6), "CET-6", "适合大学英语六级备考", "CET\n6",
            available = false, bookId = 2)
        bindBook(view.findViewById(R.id.myBookGraduate), Wordbooks.name(3), "5530 词条 · 2024 考纲词表", "考\n研",
            available = true, bookId = 3)
        bindBook(view.findViewById(R.id.myBookGraduateHigh), Wordbooks.name(4), "2444 词条 · 混合考试语料词频 ≥ 40", "词\n频",
            available = true, bookId = 4)

        view.findViewById<View>(R.id.addMyWordbook).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("添加自定义词书")
                .setMessage("自定义词书功能暂未开放，后续可在这里创建词书、添加单词。")
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun bindBook(
        row: View, name: String, description: String, coverLabel: String,
        available: Boolean, bookId: Int = 1
    ) {
        row.findViewById<TextView>(R.id.myBookName).text = name
        row.findViewById<TextView>(R.id.myBookDescription).text = description
        row.findViewById<TextView>(R.id.myBookCoverLabel).text = coverLabel
        WordbookCover.applyTo(row.findViewById(R.id.myBookCover), bookId)
        row.findViewById<TextView>(R.id.myBookStatus).apply {
            text = if (available) "查看单词" else "即将上线"
            setTextColor(Color.parseColor(if (available) "#3478F6" else "#8B929D"))
        }
        row.findViewById<View>(R.id.myBookSelected).visibility = View.GONE
        row.contentDescription = "$name，$description，${if (available) "查看单词" else "即将上线"}"
        row.setOnClickListener {
            if (available) {
                startActivity(Intent(requireContext(), Cet4Activity::class.java).apply {
                    putExtra(Cet4Activity.EXTRA_READ_ONLY, true)
                    putExtra(UploadVocabulary.EXTRA_BOOK_ID, bookId)
                })
            } else {
                Toast.makeText(requireContext(), "${name}即将上线", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
