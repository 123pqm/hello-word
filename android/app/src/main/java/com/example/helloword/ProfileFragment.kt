package com.example.helloword

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment

/** 个人页静态原型：开关仅展示交互，不读写学习配置、网络限制或存储数据。 */
class ProfileFragment : Fragment(R.layout.fragment_profile) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val placeholders = mapOf(
            R.id.profileAccount to "个人资料功能暂未开放",
            R.id.profileFavorites to "我的收藏功能暂未开放",
            R.id.profileCache to "缓存管理功能暂未开放",
            R.id.profileAbout to "关于页面暂未开放"
        )
        placeholders.forEach { (id, message) ->
            view.findViewById<View>(id).setOnClickListener {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
