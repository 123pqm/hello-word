package com.example.helloword.component

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.example.helloword.R

class component_btobar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onItemClick: ((Int) -> Unit)? = null

    init {
        LayoutInflater.from(context)
            .inflate(R.layout.component_bottom_bar, this, true)

        initClick()
    }

    private fun initClick() {

        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {
            onItemClick?.invoke(0)
        }

        findViewById<LinearLayout>(R.id.navWords).setOnClickListener {
            onItemClick?.invoke(1)
        }

        findViewById<LinearLayout>(R.id.navAi).setOnClickListener {
            onItemClick?.invoke(2)
        }

        findViewById<LinearLayout>(R.id.navVideo).setOnClickListener {
            onItemClick?.invoke(3)
        }

        findViewById<LinearLayout>(R.id.navProfile).setOnClickListener {
            onItemClick?.invoke(4)
        }
    }
}
