package com.example.helloword

import com.example.helloword.model.UploadVocabulary
import org.junit.Assert.*
import org.junit.Test

class UploadVocabularyTest {
    @Test fun bookParentUsesEntireBookEvenIfOldIdsArePresent() {
        val selection = UploadVocabulary.fromParent(UploadVocabulary.SOURCE_BOOK, intArrayOf(9))
        assertEquals(UploadVocabulary.ALL_CET4, selection.mode)
        assertTrue(selection.wordIds.isEmpty())
    }

    @Test fun wordParentKeepsAllPassedIdsIncludingOtherPages() {
        val selection = UploadVocabulary.fromParent(UploadVocabulary.SOURCE_WORDS, intArrayOf(1, 91, 4543, 1))
        assertEquals(UploadVocabulary.SELECTED_CET4, selection.mode)
        assertEquals(listOf(1, 91, 4543), selection.wordIds)
        assertTrue(selection.summary.contains("3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyWordSelectionDoesNotBecomeWholeBook() {
        UploadVocabulary.fromParent(UploadVocabulary.SOURCE_WORDS, intArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingWordSelectionDoesNotBecomeWholeBook() {
        UploadVocabulary.fromParent(UploadVocabulary.SOURCE_WORDS, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownParentIsRejected() {
        UploadVocabulary.fromParent(null, null)
    }
}
