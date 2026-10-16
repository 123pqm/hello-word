package com.example.helloword

import com.example.helloword.api.AiChatStream
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AiChatStreamTest {
    @Test fun readsIncrementalUnicodeAndEscapedNewlines() {
        val text = ": connected\r\n\r\ndata: {\"type\":\"delta\",\"content\":\"你好\"}\r\n\r\n" +
            "data: {\"type\":\"delta\",\"content\":\"\\nworld\"}\n\n" +
            "data: {\"type\":\"done\"}\n\n"
        val parts = mutableListOf<String>()
        AiChatStream.read(text.reader().buffered()) { parts.add(it) }
        assertEquals(listOf("你好", "\nworld"), parts)
    }

    @Test fun reportsUpstreamErrorAfterPartialAnswer() {
        val parts = mutableListOf<String>()
        try {
            AiChatStream.read(("data: {\"type\":\"delta\",\"content\":\"hello\"}\n\n" +
                "data: {\"type\":\"error\",\"message\":\"回复超时\"}\n\n").reader().buffered()) { parts.add(it) }
            fail("Expected error")
        } catch (e: IOException) { assertEquals("回复超时", e.message) }
        assertEquals(listOf("hello"), parts)
    }

    @Test fun rejectsDisconnectAndMalformedEvents() {
        listOf("", "data: {\"type\":\"delta\",\"content\":\"half\"}\n\n", "data: not-json\n\n").forEach { text ->
            try {
                AiChatStream.read(text.reader().buffered()) {}
                fail("Expected error")
            } catch (_: IOException) { }
        }
    }
}
