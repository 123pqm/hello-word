package com.example.helloword

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

/** 底部导航中的词书管理页，与新建视频任务的选书流程独立。 */
class MyBooksFragment : Fragment(R.layout.fragment_my_books) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindBook(view.findViewById(R.id.myBookCet4), "CET-4", "适合大学英语四级备考", "CET\n4",
            "#5085FF", "#2E63DE", available = true)
        bindBook(view.findViewById(R.id.myBookCet6), "CET-6", "适合大学英语六级备考", "CET\n6",
            "#64BABB", "#3A8E95", available = false)
        bindBook(view.findViewById(R.id.myBookGraduate), "考研词汇", "适合备考研究生", "考\n研",
            "#F1A15C", "#DF833B", available = false)

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
        lightColor: String, darkColor: String, available: Boolean
    ) {
        row.findViewById<TextView>(R.id.myBookName).text = name
        row.findViewById<TextView>(R.id.myBookDescription).text = description
        row.findViewById<TextView>(R.id.myBookCoverLabel).text = coverLabel
        row.findViewById<View>(R.id.myBookCover).background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(lightColor), Color.parseColor(darkColor))
        ).apply { cornerRadius = 6f * resources.displayMetrics.density }
        row.findViewById<TextView>(R.id.myBookStatus).apply {
            text = if (available) "当前使用" else "即将上线"
            setTextColor(Color.parseColor(if (available) "#3478F6" else "#8B929D"))
        }
        row.findViewById<ImageView>(R.id.myBookSelected)
            .setImageResource(if (available) R.drawable.vh_check else R.drawable.vh_unchecked)
        row.contentDescription = "$name，$description，${if (available) "当前使用，查看单词" else "即将上线"}"
        row.setOnClickListener {
            if (available) {
                startActivity(Intent(requireContext(), Cet4Activity::class.java).apply {
                    putExtra(Cet4Activity.EXTRA_READ_ONLY, true)
                })
            } else {
                Toast.makeText(requireContext(), "${name}即将上线", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
