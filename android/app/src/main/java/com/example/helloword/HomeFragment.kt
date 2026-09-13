package com.example.helloword

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

class HomeFragment : Fragment(R.layout.view_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.homeNewTask).setOnClickListener {
            startActivity(Intent(requireContext(), select_book::class.java))
        }
    }
}
