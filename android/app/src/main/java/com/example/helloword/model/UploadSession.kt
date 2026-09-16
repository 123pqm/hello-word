package com.example.helloword.model

enum class UploadPhase { READY, UPLOADING, SUCCEEDED, FAILED, INTERRUPTED, PROCESSING, COMPLETED, PROCESS_FAILED, UNAVAILABLE }

data class UploadSession(
    val uri: String,
    val name: String,
    val size: Long,
    val duration: String,
    val vocabulary: UploadVocabulary,
    val phase: UploadPhase = UploadPhase.READY,
    val message: String = "已选择视频，点击确认上传",
    val result: VideoData? = null,
    val flashcardsOpened: Boolean = false
) {
    val isBusy: Boolean get() = phase == UploadPhase.UPLOADING || phase == UploadPhase.PROCESSING
    val canUpload: Boolean get() = phase in setOf(UploadPhase.READY, UploadPhase.FAILED, UploadPhase.INTERRUPTED, UploadPhase.PROCESS_FAILED)
    val showConfirm: Boolean get() = !isBusy

    fun withServerStatus(status: MovieStatus): UploadSession {
        require(status.movieId == result?.movieId) { "视频任务编号不匹配" }
        return when (status.status) {
            "pending", "processing" -> copy(phase = UploadPhase.PROCESSING, message = "视频处理中，请稍候…")
            "completed" -> copy(phase = UploadPhase.COMPLETED, message = "处理完成，共匹配 ${status.matchedWordCount} 个单词")
            "failed" -> copy(phase = UploadPhase.PROCESS_FAILED, message = status.errorMessage ?: "视频处理失败，可以重试")
            else -> throw IllegalArgumentException("无法识别服务器任务状态")
        }
    }

    // A request cannot survive process death. Its server outcome may still be unknown.
    fun afterProcessRestart(): UploadSession = if (phase == UploadPhase.UPLOADING) copy(
        phase = UploadPhase.INTERRUPTED,
        message = "上次上传已中断，无法确认服务器是否收到视频；重试可能重复上传"
    ) else this
}
