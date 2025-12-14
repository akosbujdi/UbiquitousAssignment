package com.example.dodgethetraffic

import android.content.Context
import android.media.MediaPlayer
import android.media.SoundPool

class Sound private constructor(ctx: Context) {

    private val app = ctx.applicationContext

    private var music: MediaPlayer? = null
    private var currentMusicRes: Int = 0

    private val pool: SoundPool = SoundPool.Builder().setMaxStreams(6).build()
    private val sLeft  = pool.load(app, R.raw.left_switch, 1)
    private val sRight = pool.load(app, R.raw.right_switch, 1)
    private val sCrash = pool.load(app, R.raw.crash_music, 1)

    companion object {
        @Volatile private var instance: Sound? = null
        fun get(ctx: Context): Sound =
            instance ?: synchronized(this) {
                instance ?: Sound(ctx).also { instance = it }
            }
    }

    fun playMenuMusic() = playMusic(R.raw.menu_music)
    fun playGameMusic() = playMusic(R.raw.game_music)

    fun pauseMusic() { try { music?.pause() } catch (_: Exception) {} }
    fun stopMusic()  { try { music?.stop(); music?.release() } catch (_: Exception) {} ; music = null; currentMusicRes = 0 }

    fun leftSfx()  { pool.play(sLeft, 1f, 1f, 1, 0, 1f) }
    fun rightSfx() { pool.play(sRight, 1f, 1f, 1, 0, 1f) }
    fun crashSfx() { pool.play(sCrash, 1f, 1f, 2, 0, 1f) }

    private fun playMusic(resId: Int) {
        if (currentMusicRes == resId && music?.isPlaying == true) return
        stopMusic()
        currentMusicRes = resId
        music = MediaPlayer.create(app, resId)?.apply {
            isLooping = true
            setVolume(0.6f, 0.6f)
            start()
        }
    }
}
