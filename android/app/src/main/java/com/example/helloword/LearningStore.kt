package com.example.helloword

import com.example.helloword.model.UploadPhase
import com.example.helloword.model.UploadSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class MovieInfo(
    val movieId: Int?, val uri: String, val name: String,
    val size: Long, val duration: String, val phase: UploadPhase
)

/** Main-thread shared state. UploadSession remains the single persistent source of truth. */
object LearningStore {
    private var activeAccount: String? = null
    private val mutableRecentMovie = MutableStateFlow<MovieInfo?>(null)
    val recentMovie = mutableRecentMovie.asStateFlow()
    private val mutableAnalysisCompleted = MutableSharedFlow<Int>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val analysisCompleted = mutableAnalysisCompleted.asSharedFlow()
    private val navigationClaims = mutableSetOf<Pair<String, Int>>()

    internal fun activateAccount(account: String, session: UploadSession?) {
        if (activeAccount != account) {
            activeAccount = account
            mutableRecentMovie.value = null
        }
        syncSession(account, session)
    }

    internal fun syncSession(account: String, session: UploadSession?) {
        // An old account's in-flight upload must not replace the current account's home card.
        if (account != activeAccount) return
        val previous = mutableRecentMovie.value
        mutableRecentMovie.value = session?.let {
            MovieInfo(it.result?.movieId, it.uri, it.name, it.size, it.duration, it.phase)
        }
        val movieId = session?.result?.movieId
        if (movieId != null && session.phase == UploadPhase.COMPLETED && !session.flashcardsOpened &&
            (previous?.movieId != movieId || previous.phase != UploadPhase.COMPLETED)) {
            mutableAnalysisCompleted.tryEmit(movieId)
        }
    }

    internal fun claimNavigation(account: String, movieId: Int): Boolean {
        val movie = recentMovie.value
        return account == activeAccount && movie?.movieId == movieId &&
            movie.phase == UploadPhase.COMPLETED && navigationClaims.add(account to movieId)
    }

    internal fun releaseNavigation(account: String, movieId: Int) {
        navigationClaims.remove(account to movieId)
    }
}
