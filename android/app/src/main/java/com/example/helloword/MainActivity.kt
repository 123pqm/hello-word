package com.example.helloword

import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.LoginRequest
import com.example.helloword.model.RegisterRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val hello = findViewById<TextView>(R.id.tvHello)
        val welcome = findViewById<TextView>(R.id.tvWelcome)
        val accountInput = findViewById<EditText>(R.id.etAccount)
        val passwordInput = findViewById<EditText>(R.id.etPassword)
        accountInput.setText(RetrofitClient.account.orEmpty())
        val loginButton = findViewById<Button>(R.id.btnLogin)
        val text = findViewById<TextView>(R.id.text)
        val the_text = findViewById<TextView>(R.id.the_text)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val bftext =findViewById<TextView>(R.id.bf_text)
        var key: Int = 1

        fun setAuthMode(mode: Int) {
            key = mode
            if (key == 2) {
                text.text = "注册"
                the_text.text ="欢迎加入我们，让我们开始遨游词海吧"
                loginButton.text = "注册"
                tvRegister.text = "登录"
                bftext.text ="返回"
                accountInput.text.clear()
                passwordInput.text.clear()
            }
            else{

                text.text = "登录"
                the_text.text ="欢迎回来，开始今天的探索吧"
                loginButton.text = "登录"
                tvRegister.text = "立即注册"
                bftext.text ="还没有账号？"
                accountInput.text.clear()
                passwordInput.text.clear()
            }
        }

        tvRegister.setOnClickListener {
            setAuthMode(if (key == 1) 2 else 1)
        }




        loginButton.setOnClickListener {

            val account = accountInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (account.isBlank() || password.isEmpty()) {
                showMessage("请输入账号和密码")
                return@setOnClickListener // 本次点击到这里结束
            }



            // key = 1 调用登录接口，key = 2 调用注册接口。
            val submitKey = key
            loginButton.isEnabled = false
            tvRegister.isEnabled = false
            lifecycleScope.launch {
                try {
                    if (submitKey == 2) {
                        val result = RetrofitClient.apiService.register(
                            RegisterRequest(account = account, password = password)
                        )
                        showMessage(result.reply ?: "注册成功，请登录")
                        passwordInput.text.clear()
                        setAuthMode(1)
                        return@launch
                    }

                    val result = RetrofitClient.apiService.login(
                        LoginRequest(
                            account= account,
                            password = password
                        )
                    )

                    val accessToken = result.accessToken
                    if (accessToken.isNullOrBlank()) {
                        showMessage("登录响应缺少凭证，请重新登录")
                        return@launch
                    }
                    // 后续请求由 RetrofitClient 自动附加 Authorization 请求头。
                    RetrofitClient.saveSession(result)
                    showMessage(result.reply ?: "登录成功")
                    accountInput.text.clear()  // 清空账号
                    passwordInput.text.clear() // 清空密码
                    val intent = Intent(
                        this@MainActivity,
                        HomeActivity::class.java
                    )

// 打开首页
                    startActivity(intent)

// 结束登录页，按返回键时不会再回到这个登录页
                    finish()

                } catch (e: CancellationException) {
                    // 页面销毁等情况触发取消，继续传递取消信号
                    throw e

                } catch (e: HttpException) {
                    showMessage("请求失败，HTTP 状态码：${e.code()}")

                } catch (e: IOException) {
                    showMessage("网络连接失败，请稍后重试")

                } catch (e: Exception) {
                    showMessage("请求处理失败，请检查返回数据")
                } finally {
                    loginButton.isEnabled = true
                    tvRegister.isEnabled = true
                }
            }
        }

        // Hello, World 动画
        hello.translationY = 80f
        hello.scaleX = 0.85f
        hello.scaleY = 0.85f

        hello.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(900)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Welcome back 延迟出现
        welcome.translationY = 40f

        welcome.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(700)
            .setStartDelay(500)
            .start()
    }
    private fun showMessage(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }
}
