package com.example.dodgethetraffic

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

data class TrafficCar(
    var x: Float,
    var y: Float,
    val width: Float,
    val height: Float
)

class GameView(context: Context) : SurfaceView(context), Runnable {

    private var thread: Thread? = null
    private var isPlaying = false
    private val paint = Paint()

    private var startTime: Long = 0L
    private var elapsedTime: Long = 0L
    private val scorePaint = Paint().apply {
        color = Color.BLACK
        textSize = 72f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.road)
    private val playerBitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.car)
    private val blueCarBitmap = BitmapFactory.decodeResource(resources, R.drawable.bluecar)

    private var playerX = 0f
    private var playerY = 0f
    private var playerWidth = 0f
    private var playerHeight = 0f

    private val trafficCars = mutableListOf<TrafficCar>()
    private var laneX = FloatArray(2) // left and right lanes
    private var spawnTimer = System.currentTimeMillis()
    private val spawnInterval = 1800L // spawn every 1.5 seconds
    private val trafficSpeedFactor = 0.027f // adjust for screen size

    private val hitboxPadding = 0.2f

    private var initialized = false

    override fun run() {
        while (isPlaying) {
            if (!holder.surface.isValid) continue
            val canvas: Canvas = holder.lockCanvas()

            elapsedTime = System.currentTimeMillis() - startTime

            val screenWidth = canvas.width
            val screenHeight = canvas.height

            canvas.drawBitmap(
                Bitmap.createScaledBitmap(backgroundBitmap, screenWidth, screenHeight, false),
                0f,
                0f,
                paint
            )

            if (!initialized) {
                playerWidth = screenWidth * 0.4f
                playerHeight =
                    playerWidth * (playerBitmap.height.toFloat() / playerBitmap.width.toFloat())
                playerX = (screenWidth - playerWidth) / 2
                playerY = screenHeight * 0.7f

                laneX[0] = screenWidth * 0.19f
                laneX[1] = screenWidth * 0.55f

                initialized = true
            }

            val currentTime = System.currentTimeMillis()
            if (currentTime - spawnTimer > spawnInterval) {
                spawnTimer = currentTime
                val lane = (0..1).random()
                val trafficWidth = screenWidth * 0.25f
                val trafficHeight =
                    trafficWidth * (blueCarBitmap.height.toFloat() / blueCarBitmap.width.toFloat())
                trafficCars.add(
                    TrafficCar(
                        laneX[lane],
                        -trafficHeight,
                        trafficWidth,
                        trafficHeight
                    )
                )
            }

            val trafficSpeed = screenHeight * trafficSpeedFactor
            val iterator = trafficCars.iterator()
            while (iterator.hasNext()) {
                val car = iterator.next()
                car.y += trafficSpeed
                canvas.drawBitmap(
                    Bitmap.createScaledBitmap(
                        blueCarBitmap,
                        car.width.toInt(),
                        car.height.toInt(),
                        true
                    ),
                    car.x,
                    car.y,
                    paint
                )

                // Remove cars that are off-screen
                if (car.y > screenHeight) iterator.remove()

                // Basic collision detection
                val carLeft = car.x + car.width * hitboxPadding
                val carRight = car.x + car.width * (1 - hitboxPadding)
                val carTop = car.y + car.height * hitboxPadding
                val carBottom = car.y + car.height * (1 - hitboxPadding)

                val playerLeft = playerX + playerWidth * hitboxPadding
                val playerRight = playerX + playerWidth * (1 - hitboxPadding)
                val playerTop = playerY + playerHeight * hitboxPadding
                val playerBottom = playerY + playerHeight * (1 - hitboxPadding)

                if (playerRight > carLeft &&
                    playerLeft < carRight &&
                    playerBottom > carTop &&
                    playerTop < carBottom
                ) {
                    isPlaying = false // collision detected
                }
            }

            canvas.drawBitmap(
                Bitmap.createScaledBitmap(
                    playerBitmap,
                    playerWidth.toInt(),
                    playerHeight.toInt(),
                    true
                ),
                playerX,
                playerY,
                paint
            )

            val seconds = (elapsedTime / 1000).toInt()
            canvas.drawText("Score: $seconds", 50f, 100f, scorePaint)

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
        startTime = System.currentTimeMillis()
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
