package com.example.helloword

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.helloword.api.RetrofitClient
import com.example.helloword.model.UploadPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** SharedFlow broadcasts; the shared claim ensures only one foreground screen navigates. */
object FlashcardNavigation {
    fun bind(
        activity: AppCompatActivity, store: VideoUploadStore,
        onLoading: (Boolean) -> Unit = {}, onError: (String) -> Unit
    ) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    LearningStore.analysisCompleted.collect { movieId ->
                        open(activity, store, movieId, true, onLoading, onError)
                    }
                }
                // replay=0 intentionally does not replay old events. Recover only an unhandled
                // persisted result when completion happened while no screen was resumed.
                val session = store.state.value
                val movieId = session?.result?.movieId
                if (movieId != null && session.phase == UploadPhase.COMPLETED && !session.flashcardsOpened) {
                    open(activity, store, movieId, true, onLoading, onError)
                }
            }
        }
    }

    suspend fun open(
        activity: AppCompatActivity, store: VideoUploadStore, movieId: Int,
        automatic: Boolean = false, onLoading: (Boolean) -> Unit = {}, onError: (String) -> Unit
    ) {
        val account = RetrofitClient.account ?: return
        val session = store.state.value ?: return
        if (session.phase != UploadPhase.COMPLETED || session.result?.movieId != movieId ||
            (automatic && session.flashcardsOpened) ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!LearningStore.claimNavigation(account, movieId)) return
        onLoading(true)
        try {
            MovieWordsRepository.load(activity.applicationContext, movieId)
            if (RetrofitClient.account != account || store.state.value?.result?.movieId != movieId ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            activity.startActivity(Intent(activity, WordFlashcardActivity::class.java).apply {
                putExtra(WordFlashcardActivity.EXTRA_MOVIE_ID, movieId)
                putExtra(WordFlashcardActivity.EXTRA_VIDEO_URI, session.uri)
            })
            store.markFlashcardsOpened(movieId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(error.localizedMessage ?: "单词加载失败，请点击查看闪卡重试")
        } finally {
            LearningStore.releaseNavigation(account, movieId)
            onLoading(false)
        }
    }
}
