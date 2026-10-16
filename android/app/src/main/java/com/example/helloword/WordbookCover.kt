package com.example.helloword

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** 选书页与我的词书共用封面配色。 */
object WordbookCover {
    fun applyTo(cover: View, bookId: Int) {
        val colors = when (bookId) {
            2 -> "#64BABB" to "#3A8E95"
            3 -> "#F1A15C" to "#DF833B"
            4 -> "#9A80D6" to "#7157AF"
            else -> "#5085FF" to "#2E63DE"
        }
        cover.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(colors.first), Color.parseColor(colors.second))
        ).apply { cornerRadius = 6f * cover.resources.displayMetrics.density }
    }
}
