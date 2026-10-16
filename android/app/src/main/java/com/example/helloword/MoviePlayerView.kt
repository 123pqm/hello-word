package com.example.helloword.component

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.helloword.R

class MoviePlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val playerView: PlayerView
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var positionMs = 0L
    private var requestHeaders: Map<String, String> = emptyMap()
    var onPlaybackError: (() -> Unit)? = null

    init {
        LayoutInflater.from(context)
            .inflate(R.layout.view_movie_player, this, true)

        playerView = findViewById(R.id.playerView)
    }

    // 页面进入前台时创建，返回后台时释放；保留视频及进度供再次进入。
    fun initialize() {
        if (player != null) return
        val uri = videoUri ?: return
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(requestHeaders)
        val dataFactory = DefaultDataSource.Factory(context, httpFactory)
        val newPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataFactory)).build()
        player = newPlayer
        playerView.player = newPlayer
        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { onPlaybackError?.invoke() }
        })
        newPlayer.setMediaItem(MediaItem.fromUri(uri), positionMs)
        newPlayer.prepare()
    }

    fun setVideo(uri: Uri, headers: Map<String, String> = emptyMap()) {
        release()
        videoUri = uri
        requestHeaders = headers
        positionMs = 0L
        if (isAttachedToWindow) initialize()
    }

    fun play() {
        player?.play()
    }

    fun clearVideo() {
        release()
        videoUri = null
        requestHeaders = emptyMap()
        positionMs = 0L
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(position: Long) {
        positionMs = position.coerceAtLeast(0L)
        player?.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long {
        return player?.currentPosition ?: positionMs
    }

    fun release() {
        val currentPlayer = player ?: return
        positionMs = currentPlayer.currentPosition
        playerView.player = null
        currentPlayer.release()
        player = null
    }
}
