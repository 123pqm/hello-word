package com.example.helloword

import android.content.Intent
import android.widget.EditText
import android.widget.ListView
import android.widget.AdapterView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.Cet4Pages
import com.example.helloword.model.Cet4Word
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WordSearchCrashTest {
    @Test fun typingAndClearingWithPreviouslyPopulatedListDoesNotCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Preserve the user's stored login. No credentials are changed or created.
        instrumentation.runOnMainSync { RetrofitClient.initialize(context) }
        org.junit.Assume.assumeTrue("Requires an existing logged-in emulator", RetrofitClient.hasValidSession())
        for (readOnly in listOf(true, false)) {
            val activity = instrumentation.startActivitySync(Intent(context, Cet4Activity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Cet4Activity.EXTRA_READ_ONLY, readOnly)
            }) as Cet4Activity
            try {
                instrumentation.runOnMainSync {
                    val field = Cet4Activity::class.java.getDeclaredField("pages").apply { isAccessible = true }
                    val pages = field.get(activity) as Cet4Pages
                    pages.append(listOf(Cet4Word(123456, "sample", "示例", "n.")))
                    pages.select(123456, true)
                    val list = activity.findViewById<ListView>(R.id.cet4Words)
                    (list.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                    list.layout(0, 0, 600, 900)
                    val search = activity.findViewById<EditText>(R.id.wordbookSearch)
                    for (text in listOf("a", "ap", "apple", "", "z", "")) {
                        search.setText(text)
                        if (text.isNotEmpty()) {
                            assertEquals(0, list.adapter.count)
                            assertEquals(AdapterView.INVALID_ROW_ID, list.adapter.getItemId(0))
                        } else assertTrue(list.adapter.count > 0)
                        assertTrue(pages.selectedIds.contains(123456))
                    }
                    assertFalse(activity.isFinishing)
                }
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
