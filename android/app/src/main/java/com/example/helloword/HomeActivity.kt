package com.example.helloword

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.helloword.component.component_btobar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    // 当前选中的按钮：0 首页，1 生词卡片，2 片段学习，3 统计，4 我的。
    private val curindex = MutableStateFlow(0)
    private lateinit var bottomBar: component_btobar

    private val textIds = intArrayOf(
        R.id.textHome, R.id.textWords, R.id.textVideo,
        R.id.textStats, R.id.textProfile
    )
    private val iconIds = intArrayOf(
        R.id.iconHome, R.id.iconWords, R.id.iconVideo,
        R.id.iconStats, R.id.iconProfile
    )
    private val itemIds = intArrayOf(
        R.id.navHome, R.id.navWords, R.id.navVideo,
        R.id.navStats, R.id.navProfile
    )
    private val selectedColor = Color.parseColor("#3478F6")
    private val normalColor = Color.parseColor("#B0B0B0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // 避开系统状态栏和手势导航区域。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val homeRoot = findViewById<View>(R.id.homeRoot)
        ViewCompat.setOnApplyWindowInsetsListener(homeRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(homeRoot)

        bottomBar = findViewById(R.id.bottomBar)
        curindex.value = (savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: 0)
            .coerceIn(itemIds.indices)

        if (savedInstanceState == null) {
            showHomePage()
        }

        // 点击只修改选中状态，并执行对应页面的操作。
        bottomBar.onItemClick = click@{ index ->
            if (index !in itemIds.indices) return@click
            curindex.value = index

            when (index) {
                0 -> {
                    showHomePage()
                    println("首页")
                }
                1 -> println("生词卡片")
                2 -> println("片段学习")
                3 -> println("统计")
                4 -> println("我的")
            }
        }

        // 监听状态：首次显示、选中项变化或返回此页面时，统一更新全部按钮。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                curindex.collect { index ->
                    updateBottomBar(index)
                }
            }
        }
    }

    private fun updateBottomBar(selectedIndex: Int) {
        for (i in itemIds.indices) {
            val selected = i == selectedIndex
            val color = if (selected) selectedColor else normalColor

            bottomBar.findViewById<View>(itemIds[i]).isSelected = selected
            bottomBar.findViewById<TextView>(textIds[i]).apply {
                setTextColor(color)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
            // 与底部栏 XML 中的 app:tint 对应，图标和文字保持同色。
            bottomBar.findViewById<AppCompatImageView>(iconIds[i])
                .supportImageTintList = ColorStateList.valueOf(color)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_SELECTED_TAB, curindex.value)
        super.onSaveInstanceState(outState)
    }

    private fun showHomePage() {
        if (supportFragmentManager.findFragmentById(R.id.pageContainer) is HomeFragment) return
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.pageContainer, HomeFragment())
            .commit()
    }

    private companion object {
        const val KEY_SELECTED_TAB = "selected_bottom_tab"
    }
}
