package com.example.helloword

import android.content.Intent
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import com.example.helloword.api.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.SwitchCompat

/** 个人入口和闪卡显示设置。 */
class ProfileFragment : Fragment(R.layout.fragment_profile) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val logoutButton = view.findViewById<Button>(R.id.profileLogout)
        logoutButton.setOnClickListener {
            if (!logoutButton.isEnabled) return@setOnClickListener
            val token = RetrofitClient.token
            logoutButton.isEnabled = false
            logoutButton.text = "正在退出…"
            // Activity lifetime allows the request to finish if the profile tab is switched.
            val host = requireActivity()
            host.lifecycleScope.launch {
                try {
                    val response = token?.let { RetrofitClient.apiService.logout("Bearer $it") }
                    if (response != null && !response.isSuccessful && response.code() != 401) {
                        Toast.makeText(host, "退出失败（${response.code()}），请重试", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    // A delayed response must not clear a newly signed-in session.
                    if (RetrofitClient.token != token) return@launch
                    RetrofitClient.clearSession()
                    host.startActivity(Intent(host, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(host, "无法连接服务器，退出未完成，请重试", Toast.LENGTH_LONG).show()
                } finally {
                    logoutButton.isEnabled = true
                    logoutButton.text = "退出登录"
                }
            }
        }
        val settings = FlashcardSettings(requireContext())
        val defaults = settings.read()
        view.findViewById<SwitchCompat>(R.id.profileShowMeaning).apply {
            isSaveEnabled = false
            isChecked = defaults.showMeaning
            setOnCheckedChangeListener { _, checked -> settings.setShowMeaning(checked) }
        }
        view.findViewById<SwitchCompat>(R.id.profileShowSentence).apply {
            isSaveEnabled = false
            isChecked = defaults.showSentence
            setOnCheckedChangeListener { _, checked -> settings.setShowSentence(checked) }
        }
        val placeholders = mapOf(
            R.id.profileAccount to "个人资料功能暂未开放",
            R.id.profileCache to "缓存管理功能暂未开放",
            R.id.profileAbout to "关于页面暂未开放"
        )
        view.findViewById<View>(R.id.profileFavorites).setOnClickListener {
            startActivity(android.content.Intent(requireContext(), FavoritesActivity::class.java))
        }
        placeholders.forEach { (id, message) ->
            view.findViewById<View>(id).setOnClickListener {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
