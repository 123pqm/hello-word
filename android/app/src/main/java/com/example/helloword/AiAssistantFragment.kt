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
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.helloword.api.AiChatClient
import com.example.helloword.api.AiChatMessage
import com.example.helloword.api.AiChatRequest
import okhttp3.Call

/** 接收闪卡定位，逐步显示真实 AI 回复，保留本次会话上下文。 */
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
    private data class Entry(val role: String, var text: String, var complete: Boolean = false, var note: String = "")
    private val entries = mutableListOf<Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private var activeCall: Call? = null
    private var generation = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        contextRemoved = savedInstanceState?.getBoolean(STATE_CONTEXT_REMOVED) ?: contextRemoved
        if (savedInstanceState != null) lastSuggestedPrompt = savedInstanceState.getString(STATE_SUGGESTED_PROMPT)
        if (savedInstanceState != null) {
            entries.clear()
            savedInstanceState.getParcelableArrayList<Bundle>("messages")?.forEach {
                entries.add(Entry(it.getString("role").orEmpty(), it.getString("text").orEmpty(),
                    it.getBoolean("complete"), it.getString("note").orEmpty()))
            }
        }
        val word = arguments?.getString(ARG_WORD)?.trim()?.takeIf { it.isNotEmpty() }
        val sentence = arguments?.getString(ARG_SENTENCE)?.trim()?.takeIf { it.isNotEmpty() }
        val contextArea = view.findViewById<View>(R.id.aiWordContext)
        val contextLabel = view.findViewById<TextView>(R.id.aiContextWord)
        contextLabel.text = word?.let { "正在学习：$it" }.orEmpty()
        contextArea.visibility = if (word != null && !contextRemoved) View.VISIBLE else View.GONE
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
            if (activeCall != null) stopReply("已停止生成") else send(view, word.orEmpty(), sentence.orEmpty())
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (activeCall == null) send(view, word.orEmpty(), sentence.orEmpty())
                true
            } else false
        }
        view.findViewById<View>(R.id.aiHistory).setOnClickListener {
            stopReply("已停止生成")
            entries.clear()
            view.findViewById<LinearLayout>(R.id.aiConversation).removeAllViews()
            showMessage("已开始新对话")
        }
        entries.forEach { addBubble(view, it) }
        updateSend(view)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CONTEXT_REMOVED, contextRemoved)
        outState.putString(STATE_SUGGESTED_PROMPT, lastSuggestedPrompt)
        outState.putParcelableArrayList("messages", ArrayList(entries.map { entry -> Bundle().apply {
            putString("role", entry.role); putString("text", entry.text); putBoolean("complete", entry.complete)
            putString("note", entry.note.ifEmpty { if (!entry.complete) "回复已中断，请重新提问" else "" })
        } }))
        super.onSaveInstanceState(outState)
    }

    private fun showMessage(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun send(root: View, word: String, sentence: String) {
        val input = root.findViewById<EditText>(R.id.aiQuestion)
        val question = input.text.toString().trim()
        if (question.isEmpty()) { showMessage("请先输入问题"); return }
        // 只发送完整成功的问答，保留最近 10 轮，并限制上下文总长度。
        val history = entries.chunked(2).filter { it.size == 2 && it.all { entry -> entry.complete } }
            .takeLast(10).flatten().map { AiChatMessage(it.role, it.text) }.toMutableList()
        while (history.sumOf { it.content.length } + question.length > 30000 && history.size >= 2) {
            history.removeAt(0); history.removeAt(0)
        }
        history.add(AiChatMessage("user", question))
        if (entries.size >= 40) {
            entries.removeAt(0); entries.removeAt(0)
            root.findViewById<LinearLayout>(R.id.aiConversation).apply { removeViewAt(0); removeViewAt(0) }
        }
        val user = Entry("user", question, true)
        val reply = Entry("assistant", "")
        entries.add(user); entries.add(reply)
        addBubble(root, user)
        val replyView = addBubble(root, reply)
        input.text.clear()
        lastSuggestedPrompt = null
        val requestId = ++generation
        fun update(action: () -> Unit) {
            handler.post { if (generation == requestId && view === root) action() }
        }
        activeCall = AiChatClient.start(
            AiChatRequest(history, if (contextRemoved) "" else word, if (contextRemoved) "" else sentence),
            onDelta = { delta -> update {
                reply.text += delta
                renderReply(replyView, reply)
                scrollToBottom(root)
            } },
            onDone = { update {
                reply.complete = true
                activeCall = null
                renderReply(replyView, reply)
                updateSend(root)
            } },
            onError = { message -> update {
                reply.note = message
                activeCall = null
                renderReply(replyView, reply)
                updateSend(root)
                if (input.text.isEmpty()) input.setText(question)
            } },
        )
        updateSend(root)
        scrollToBottom(root)
    }

    private fun addBubble(root: View, entry: Entry): TextView {
        val isUser = entry.role == "user"
        val bubble = TextView(requireContext()).apply {
            textSize = 15f
            setTextColor(Color.parseColor(if (isUser) "#FFFFFF" else "#303641"))
            setBackgroundResource(if (isUser) R.drawable.bg_ai_question else R.drawable.bg_profile_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setLineSpacing(dp(4).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
                if (isUser) marginStart = dp(32) else marginEnd = dp(16)
            }
            setOnLongClickListener {
                if (entry.text.isNotEmpty()) {
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("AI 对话", entry.text))
                    showMessage("已复制")
                }
                true
            }
        }
        renderReply(bubble, entry)
        root.findViewById<LinearLayout>(R.id.aiConversation).addView(bubble)
        return bubble
    }

    private fun renderReply(target: TextView, entry: Entry) {
        target.text = if (entry.role == "user") entry.text else buildString {
            append("AI 学习助手\n\n")
            append(entry.text.ifEmpty { if (entry.note.isEmpty() && !entry.complete) "正在思考…" else "" })
            if (entry.note.isNotEmpty()) append("\n\n${entry.note}")
        }
    }

    private fun stopReply(note: String) {
        generation++
        activeCall?.cancel()
        if (activeCall != null) entries.lastOrNull()?.let { entry ->
            entry.note = note
            val container = view?.findViewById<LinearLayout>(R.id.aiConversation)
            if (container != null) (container.getChildAt(container.childCount - 1) as? TextView)?.let { renderReply(it, entry) }
        }
        activeCall = null
        view?.let { updateSend(it) }
    }

    private fun updateSend(root: View) {
        root.findViewById<ImageButton>(R.id.aiSend).apply {
            setImageResource(if (activeCall != null) android.R.drawable.ic_media_pause else R.drawable.ic_ai_send)
            contentDescription = if (activeCall != null) "停止生成" else "发送问题"
        }
    }

    private fun scrollToBottom(root: View) {
        root.findViewById<ScrollView>(R.id.aiContentScroll).post {
            if (view === root) root.findViewById<ScrollView>(R.id.aiContentScroll).let { scroll ->
                scroll.scrollTo(0, scroll.getChildAt(0).height)
            }
        }
    }

    override fun onDestroyView() {
        stopReply("回复已中断，请重新提问")
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
