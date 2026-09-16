package com.example.helloword.model

data class UploadVocabulary(val mode: String, val wordIds: List<Int>) {
    val summary: String
        get() = if (mode == ALL_CET4) "CET-4 · 整本词库" else "CET-4 · 已选 ${wordIds.size} 个单词"

    companion object {
        const val EXTRA_SOURCE = "upload_source"
        const val EXTRA_WORD_IDS = "upload_word_ids"
        const val SOURCE_BOOK = "book_select"
        const val SOURCE_WORDS = "activity_cet4"
        const val ALL_CET4 = "all_cet4"
        const val SELECTED_CET4 = "selected_cet4"

        fun fromParent(source: String?, wordIds: IntArray?): UploadVocabulary {
            return when (source) {
                SOURCE_BOOK -> UploadVocabulary(ALL_CET4, emptyList())
                SOURCE_WORDS -> {
                    val ids = wordIds?.toList()?.distinct().orEmpty()
                    require(ids.isNotEmpty() && ids.all { it > 0 }) { "请先选择需要匹配的四级单词" }
                    UploadVocabulary(SELECTED_CET4, ids)
                }
                else -> throw IllegalArgumentException("请从词书或单词选择页进入")
            }
        }
    }
}
