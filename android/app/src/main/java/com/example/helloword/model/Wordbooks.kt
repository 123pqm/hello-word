package com.example.helloword.model

object Wordbooks {
    const val CET4 = 1
    const val NETEM = 3
    const val NETEM_HIGH = 4
    val ids = setOf(CET4, NETEM, NETEM_HIGH)
    fun name(id: Int) = when (id) {
        NETEM -> "考研词汇全表"
        NETEM_HIGH -> "考研词汇·词频优先"
        else -> "CET-4"
    }
    const val NETEM_SOURCE = "来源：exam-data/NETEMVocabulary（2024 考纲）\n词频来自四六级、考研、专四专八混合试卷，非纯考研真题频率。\n高频版筛选出现至少 40 次的词条。按原始释义导入，筛选及排序由本项目完成。\n数据许可：CC BY-NC-SA 4.0（署名、非商业、相同方式共享）。\nhttps://github.com/exam-data/NETEMVocabulary\nhttps://creativecommons.org/licenses/by-nc-sa/4.0/"
}
