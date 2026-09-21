package com.example.helloword

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/** 接收闪卡的真实单词定位；AI 回答仍为演示，不联网。 */
class AiAssistantFragment : Fragment(R.layout.fragment_ai_assistant) {
    companion object {
        const val ARG_SHOW_BACK = "ai_show_back"
        const val ARG_WORD = "ai_word"
        const val ARG_SENTENCE = "ai_sentence"
        private const val STATE_CONTEXT_REMOVED = "ai_context_removed"
        private const val STATE_SUGGESTED_PROMPT = "ai_suggested_prompt"
    }

    private var contextRemoved = false
    private var lastSuggestedPrompt: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        contextRemoved = savedInstanceState?.getBoolean(STATE_CONTEXT_REMOVED) ?: contextRemoved
        if (savedInstanceState != null) lastSuggestedPrompt = savedInstanceState.getString(STATE_SUGGESTED_PROMPT)
        val word = arguments?.getString(ARG_WORD)?.trim()?.takeIf { it.isNotEmpty() }
        val sentence = arguments?.getString(ARG_SENTENCE)?.trim()?.takeIf { it.isNotEmpty() }
        val contextArea = view.findViewById<View>(R.id.aiWordContext)
        val contextLabel = view.findViewById<TextView>(R.id.aiContextWord)
        contextLabel.text = word?.let { "正在学习：$it" }.orEmpty()
        contextArea.visibility = if (word != null && !contextRemoved) View.VISIBLE else View.GONE
        // 从真实闪卡进入时，不展示与当前单词无关的 beautiful 示例对话。
        view.findViewById<View>(R.id.aiDemoConversation).visibility = if (word == null) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.aiBack).apply {
            visibility = if (arguments?.getBoolean(ARG_SHOW_BACK) == true) View.VISIBLE else View.INVISIBLE
            setOnClickListener { requireActivity().finish() }
        }
        val input = view.findViewById<EditText>(R.id.aiQuestion)
        listOf(R.id.aiExplainWord, R.id.aiTranslateSentence, R.id.aiAnalyzeLine, R.id.aiPracticeEnglish).forEach { id ->
            view.findViewById<View>(id).setOnClickListener {
                val activeWord = word.takeUnless { contextRemoved }
                val activeSentence = sentence.takeIf { activeWord != null }
                val prompt = when (id) {
                    R.id.aiExplainWord -> activeWord?.let { "请解释 $it 的用法和常见搭配。" }
                        ?: "请解释这个单词的用法和常见搭配："
                    R.id.aiTranslateSentence -> "请翻译这句话：${activeSentence.orEmpty()}"
                    R.id.aiAnalyzeLine -> "请分析这句台词的语法和表达：${activeSentence.orEmpty()}"
                    else -> activeWord?.let { "请用 $it 给我出一道英语练习题。" }
                        ?: "请给我出一道英语练习题。"
                }
                lastSuggestedPrompt = prompt
                input.setText(prompt)
                input.setSelection(input.text.length)
                input.requestFocus()
            }
        }
        view.findViewById<View>(R.id.aiCloseContext).setOnClickListener {
            contextRemoved = true
            contextArea.visibility = View.GONE
            contextLabel.text = ""
            // 未经用户修改的快捷提问一并清除，避免移除定位后仍带入旧单词。
            if (lastSuggestedPrompt != null && input.text.toString() == lastSuggestedPrompt) input.text.clear()
            lastSuggestedPrompt = null
        }
        view.findViewById<View>(R.id.aiSend).setOnClickListener {
            showMessage(if (input.text.isNullOrBlank()) "请先输入问题" else "当前为页面演示，AI 对话暂未接入")
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                view.findViewById<View>(R.id.aiSend).performClick()
                true
            } else false
        }
        view.findViewById<View>(R.id.aiHistory).setOnClickListener { showMessage("对话历史功能暂未开放") }
        view.findViewById<View>(R.id.aiSaveAnswer).setOnClickListener { showMessage("收藏功能暂未开放") }
        view.findViewById<View>(R.id.aiCopyAnswer).setOnClickListener {
            val answer = view.findViewById<TextView>(R.id.aiAnswer).text
            val example = view.findViewById<TextView>(R.id.aiExample).text
            val translation = view.findViewById<TextView>(R.id.aiExampleMeaning).text
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AI 示例回答", "$answer\n\n$example\n$translation"))
            showMessage("已复制示例回答")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CONTEXT_REMOVED, contextRemoved)
        outState.putString(STATE_SUGGESTED_PROMPT, lastSuggestedPrompt)
        super.onSaveInstanceState(outState)
    }

    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
