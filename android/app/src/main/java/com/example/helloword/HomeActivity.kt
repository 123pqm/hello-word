package com.example.helloword

import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.widget.Toast
import com.example.helloword.api.RetrofitClient

class HomeActivity : AppCompatActivity() {

    // 当前选中的按钮：0 首页，1 我的词书，2 AI，3 我的观影，4 我的。
    private val curindex = MutableStateFlow(0)
    private lateinit var bottomBar: component_btobar

    private val textIds = intArrayOf(
        R.id.textHome, R.id.textWords, 0,
        R.id.textVideo, R.id.textProfile
    )
    private val iconIds = intArrayOf(
        R.id.iconHome, R.id.iconWords, R.id.iconAi,
        R.id.iconVideo, R.id.iconProfile
    )
    private val itemIds = intArrayOf(
        R.id.navHome, R.id.navWords, R.id.navAi,
        R.id.navVideo, R.id.navProfile
    )
    private val selectedColor = Color.parseColor("#3478F6")
    private val normalColor = Color.parseColor("#B0B0B0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        // 回到前台即打卡；持续停留时在北京时间午夜自动刷新。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    val seconds = CheckinStore.refresh()
                    delay(seconds * 1000)
                }
            }
        }
        RetrofitClient.account?.let { account ->
            val store = VideoUploadStore.forAccount(applicationContext, account)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) { store.observeProcessing() }
            }
            FlashcardNavigation.bind(this, store, onError = { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            })
        }

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
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
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
            if (index != 2) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            if (index == 1) {
                curindex.value = index
                showMyBooksPage()
                return@click
            }
            if (index == 3) {
                // 观影历史是独立页面，返回后保留原页面的选中状态。
                startActivity(Intent(this, MyMoviesActivity::class.java))
                return@click
            }
            curindex.value = index

            when (index) {
                0 -> {
                    showHomePage()
                    println("首页")
                }
                2 -> showAiPage()
                4 -> showProfilePage()
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
            if (textIds[i] != 0) {
                bottomBar.findViewById<TextView>(textIds[i]).apply {
                    setTextColor(color)
                    setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                }
            }
            // 与底部栏 XML 中的 app:tint 对应，图标和文字保持同色。
            bottomBar.findViewById<AppCompatImageView>(iconIds[i])
                .supportImageTintList = if (iconIds[i] == R.id.iconAi) null else ColorStateList.valueOf(color)
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

    private fun showMyBooksPage() {
        if (supportFragmentManager.findFragmentById(R.id.pageContainer) is MyBooksFragment) return
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.pageContainer, MyBooksFragment())
            .commit()
    }

    private fun showProfilePage() {
        if (supportFragmentManager.findFragmentById(R.id.pageContainer) is ProfileFragment) return
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.pageContainer, ProfileFragment())
            .commit()
    }

    private fun showAiPage() {
        if (supportFragmentManager.findFragmentById(R.id.pageContainer) is AiAssistantFragment) return
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.pageContainer, AiAssistantFragment())
            .commit()
    }

    private companion object {
        const val KEY_SELECTED_TAB = "selected_bottom_tab"
    }
}
