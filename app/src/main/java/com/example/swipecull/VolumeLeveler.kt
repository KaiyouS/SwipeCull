package com.example.swipecull

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.media3.exoplayer.ExoPlayer

/**
 * Evens out loudness between clips: a compressor lifts quiet audio and squashes loud peaks,
 * then a limiter stops clipping. It's applied to the player's audio session, so it affects
 * only this app's playback (nothing is changed in the files).
 */
class VolumeLeveler(private val player: ExoPlayer) {
    private var effect: DynamicsProcessing? = null

    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) = attach(audioSessionId)
    }

    init {
        if (Build.VERSION.SDK_INT >= 28) {
            player.addListener(listener)
            attach(player.audioSessionId)
        }
    }

    private fun attach(sessionId: Int) {
        if (Build.VERSION.SDK_INT < 28 || sessionId == 0) return
        runCatching {
            effect?.release()
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, 2,
                false, 0, true, 1, false, 0, true,
            ).build()
            val dp = DynamicsProcessing(0, sessionId, config)
            val band = DynamicsProcessing.MbcBand(
                true, 20000f,  // enabled, covers the whole spectrum
                10f, 250f,     // attack / release ms
                4f, -40f, 6f,  // ratio, threshold dB, knee
                -90f, 1f,      // noise gate, expander
                0f, 15f,       // pre gain, post gain (makeup) dB
            )
            dp.setMbcBandAllChannelsTo(0, band)
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f),
            )
            dp.enabled = true
            effect = dp
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= 28) player.removeListener(listener)
        runCatching { effect?.release() }
        effect = null
    }
}
