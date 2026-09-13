package com.example.helloword

import com.example.helloword.model.Cet4Pages
import com.example.helloword.model.Cet4Word
import org.junit.Assert.*
import org.junit.Test

class Cet4PagesTest {
    private fun word(id: Int) = Cet4Word(id, "word$id", "meaning", null)

    @Test fun rangesAdvanceByTenAndKeepEarlierWords() {
        val pages = Cet4Pages()
        assertEquals(1, pages.nextStart)
        assertEquals(10, pages.nextEnd)
        pages.append((1..10).map { word(it) })
        assertEquals(11, pages.nextStart)
        assertEquals(20, pages.nextEnd)
        pages.select(1, true)
        pages.append((11..20).map { word(it) })
        assertEquals(20, pages.words.size)
        assertTrue(1 in pages.selectedIds)
        assertEquals(21, pages.nextStart)
    }

    @Test fun emptyResponseEndsPagingWithoutAdvancingRange() {
        val pages = Cet4Pages()
        pages.append(emptyList())
        assertTrue(pages.finished)
        assertEquals(1, pages.nextStart)
    }

    @Test fun partialRangeDoesNotEndAndDuplicatesAreIgnored() {
        val pages = Cet4Pages()
        pages.append(listOf(word(1), word(3)))
        assertFalse(pages.finished)
        pages.append(listOf(word(3), word(11)))
        assertEquals(listOf(1, 3, 11), pages.words.map { it.id })
        pages.select(3, true)
        pages.select(3, false)
        assertFalse(3 in pages.selectedIds)
    }
}
