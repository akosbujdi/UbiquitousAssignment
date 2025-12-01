package com.example.dodgethetraffic

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

class GameView(context: Context) : SurfaceView(context), Runnable {

    private var thread: Thread? = null
    private var isPlaying = false
    private val paint = Paint()

    private val backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.road)
    private val playerBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.car)
    private var playerX = 0f
    private var playerY = 0f
    private var playerWidth = 0f
    private var playerHeight = 0f

    private var initialized = false

    override fun run() {
        while (isPlaying) {
            if (!holder.surface.isValid) continue
            val canvas: Canvas = holder.lockCanvas()

            canvas.drawBitmap(
                Bitmap.createScaledBitmap(backgroundBitmap, canvas.width, canvas.height, false),
                0f,
                0f,
                paint
            )

            if (!initialized) {
                playerWidth = canvas.width * 0.3f
                playerHeight = playerWidth * (playerBitmap.height.toFloat() / playerBitmap.width.toFloat())
                playerX = (canvas.width - playerWidth) / 2
                playerY = canvas.height * 0.7f
                initialized = true
            }

            canvas.drawBitmap(
                Bitmap.createScaledBitmap(playerBitmap, playerWidth.toInt(), playerHeight.toInt(), true),
                playerX,
                playerY,
                paint
            )

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Move player horizontally, keep within screen bounds
                playerX = event.x - playerWidth / 2
                if (playerX < 0) playerX = 0f
                if (playerX + playerWidth > width) playerX = (width - playerWidth).toFloat()
            }
        }
        return true
    }
}
