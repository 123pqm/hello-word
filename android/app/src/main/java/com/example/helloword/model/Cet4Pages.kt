package com.example.helloword.model

class Cet4Pages {
    val words = mutableListOf<Cet4Word>()
    val selectedIds = linkedSetOf<Int>()
    var nextStart = 1
        private set
    val nextEnd: Int get() = nextStart + 9
    var finished = false
        private set

    // 请求成功后才推进区间；失败时保持原区间以便重试。
    fun append(items: List<Cet4Word>) {
        if (items.isEmpty()) { finished = true; return }
        val existing = words.mapTo(hashSetOf()) { it.id }
        words.addAll(items.filter { existing.add(it.id) })
        nextStart += 10
    }
    fun select(id: Int, checked: Boolean) {
        if (checked) selectedIds.add(id) else selectedIds.remove(id)
    }
}
