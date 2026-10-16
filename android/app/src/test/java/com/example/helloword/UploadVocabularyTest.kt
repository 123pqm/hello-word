package com.example.helloword

import com.example.helloword.model.UploadVocabulary
import org.junit.Assert.*
import org.junit.Test

class UploadVocabularyTest {
    @Test fun graduateSelectionKeepsBookIdentityAndScope() {
        val selected = UploadVocabulary.fromParent(UploadVocabulary.SOURCE_WORDS, intArrayOf(7, 90), 4)
        assertEquals(4, selected.effectiveBookId)
        assertEquals(UploadVocabulary.SELECTED_BOOK, selected.mode)
        assertTrue(selected.summary.contains("词频优先"))
        assertTrue(selected.isSelected)
        val whole = UploadVocabulary.fromParent(UploadVocabulary.SOURCE_BOOK, intArrayOf(7), 3)
        assertEquals(3, whole.effectiveBookId)
        assertEquals(UploadVocabulary.ALL_BOOK, whole.mode)
        assertTrue(whole.wordIds.isEmpty())
    }

    @Test fun legacyDraftStillUsesCet4AndGraduateDraftSurvivesRestart() {
        val gson = com.google.gson.Gson()
        val old = gson.fromJson("""{"mode":"selected_cet4","wordIds":[1]}""", UploadVocabulary::class.java)
        assertEquals(1, old.effectiveBookId)
        val selected = UploadVocabulary.fromParent(UploadVocabulary.SOURCE_WORDS, intArrayOf(1), 3)
        assertEquals(selected, gson.fromJson(gson.toJson(selected), UploadVocabulary::class.java))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unavailableBookNeverFallsBackToCet4() {
        UploadVocabulary.fromParent(UploadVocabulary.SOURCE_BOOK, null, 2)
    }
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
