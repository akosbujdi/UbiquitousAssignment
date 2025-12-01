package com.example.dodgethetraffic

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent

class GameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the custom GameView
        gameView = GameView(this)
        setContentView(gameView)
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
    }
}

// Custom GameView for simple drawing
class GameView(context: GameActivity) : SurfaceView(context), Runnable {

    private var thread: Thread? = null
    private var isPlaying = false
    private val paint = Paint()
    private val playerWidth = 180f
    private val playerHeight = 280f
    private var playerX = 450f
    private var playerY = 1500f

    init {
        paint.color = Color.RED
    }

    override fun run() {
        while (isPlaying) {
            if (!holder.surface.isValid) continue
            val canvas: Canvas = holder.lockCanvas()
            canvas.drawColor(Color.BLACK) // background
            canvas.drawRect(playerX, playerY, playerX + playerWidth, playerY + playerHeight, paint)
            holder.unlockCanvasAndPost(canvas)
        }
    }

    fun pause() {
        isPlaying = false
        try {
            thread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun resume() {
        isPlaying = true
        thread = Thread(this)
        thread?.start()
    }

    // Simple touch to move left/right (temporary control before micro:bit)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
            playerX = event.x - playerWidth / 2
        }
        return true
    }
}

