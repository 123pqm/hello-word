package com.example.helloword.model

data class UploadVocabulary(val mode: String, val wordIds: List<Int>, val bookId: Int? = null) {
    // 旧的本地上传草稿没有 bookId，继续指向四级。
    val effectiveBookId: Int get() = bookId ?: Wordbooks.CET4
    val isSelected: Boolean get() = mode == SELECTED_CET4 || mode == SELECTED_BOOK
    val summary: String
        get() = Wordbooks.name(effectiveBookId) + if (isSelected) " · 已选 ${wordIds.size} 个单词" else " · 整本词库"

    companion object {
        const val EXTRA_SOURCE = "upload_source"
        const val EXTRA_WORD_IDS = "upload_word_ids"
        const val EXTRA_BOOK_ID = "upload_book_id"
        const val SOURCE_BOOK = "book_select"
        const val SOURCE_WORDS = "activity_cet4"
        const val ALL_CET4 = "all_cet4"
        const val SELECTED_CET4 = "selected_cet4"
        const val ALL_BOOK = "all_book"
        const val SELECTED_BOOK = "selected_book"

        fun fromParent(source: String?, wordIds: IntArray?, bookId: Int = Wordbooks.CET4): UploadVocabulary {
            require(bookId in Wordbooks.ids) { "该词书尚未开放" }
            return when (source) {
                SOURCE_BOOK -> UploadVocabulary(if (bookId == Wordbooks.CET4) ALL_CET4 else ALL_BOOK, emptyList(), bookId)
                SOURCE_WORDS -> {
                    val ids = wordIds?.toList()?.distinct().orEmpty()
                    require(ids.isNotEmpty() && ids.all { it > 0 }) { "请先选择需要匹配的单词" }
                    UploadVocabulary(if (bookId == Wordbooks.CET4) SELECTED_CET4 else SELECTED_BOOK, ids, bookId)
                }
                else -> throw IllegalArgumentException("请从词书或单词选择页进入")
            }
        }
    }
}
