package com.example.dodgethetraffic

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.abs

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
    private var score = 0

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

    // ---- LANE SETUP (3 lanes) ----
    private val laneCount = 3
    private var laneX = FloatArray(laneCount)
    private var currentLane = 1 // start in middle lane

    private var spawnTimer = System.currentTimeMillis()
    private val spawnInterval = 1800L
    private val trafficSpeedFactor = 0.027f
    private val hitboxPadding = 0.2f
    private var initialized = false

    override fun run() {
        while (isPlaying) {
            if (!holder.surface.isValid) continue
            val canvas: Canvas = holder.lockCanvas()

            elapsedTime = System.currentTimeMillis() - startTime

            val screenWidth = canvas.width
            val screenHeight = canvas.height

            // Draw background
            canvas.drawBitmap(
                Bitmap.createScaledBitmap(backgroundBitmap, screenWidth, screenHeight, false),
                0f, 0f, paint
            )

            // One-time layout init
            if (!initialized) {
                // Make car size reasonable for 3 lanes
                playerWidth = screenWidth * 0.24f
                playerHeight = playerWidth * (playerBitmap.height.toFloat() / playerBitmap.width.toFloat())
                playerY = screenHeight * 0.7f

                // Same width for traffic cars
                val trafficWidth = screenWidth * 0.24f
                val roadLeft = screenWidth * 0.08f
                val roadRight = screenWidth * 0.92f

                val usable = (roadRight - roadLeft - trafficWidth)
                val step = usable / (laneCount - 1)

                for (i in 0 until laneCount) {
                    laneX[i] = roadLeft + i * step
                }

                // Place player in current lane
                playerX = laneX[currentLane]

                initialized = true
            }

            // Spawn traffic cars
            val currentTime = System.currentTimeMillis()
            if (currentTime - spawnTimer > spawnInterval) {
                spawnTimer = currentTime

                val lane = (0 until laneCount).random()
                val trafficWidth = screenWidth * 0.24f
                val trafficHeight = trafficWidth * (blueCarBitmap.height.toFloat() / blueCarBitmap.width.toFloat())

                trafficCars.add(
                    TrafficCar(
                        x = laneX[lane],
                        y = -trafficHeight,
                        width = trafficWidth,
                        height = trafficHeight
                    )
                )
            }

            // Move traffic + collisions
            val trafficSpeed = screenHeight * trafficSpeedFactor
            val iterator = trafficCars.iterator()
            while (iterator.hasNext()) {
                val car = iterator.next()
                car.y += trafficSpeed

                canvas.drawBitmap(
                    Bitmap.createScaledBitmap(blueCarBitmap, car.width.toInt(), car.height.toInt(), true),
                    car.x, car.y, paint
                )

                if (car.y > screenHeight) iterator.remove()

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
                    isPlaying = false
                    post { showGameOverDialog() }
                }
            }

            // Draw player
            canvas.drawBitmap(
                Bitmap.createScaledBitmap(playerBitmap, playerWidth.toInt(), playerHeight.toInt(), true),
                playerX, playerY, paint
            )

            // Score
            score = (elapsedTime / 1000).toInt()
            canvas.drawText("Score: $score", 50f, 100f, scorePaint)

            holder.unlockCanvasAndPost(canvas)
        }
    }

    fun pause() {
        isPlaying = false
        try { thread?.join() } catch (_: InterruptedException) {}
    }

    fun resume() {
        isPlaying = true
        startTime = System.currentTimeMillis()
        thread = Thread(this)
        thread?.start()
    }

    // ✅ This is what GameActivity should call when it receives "LANE:0/1/2"
    fun movePlayerToLane(lane: Int) {
        val clamped = lane.coerceIn(0, laneCount - 1)
        currentLane = clamped
        if (initialized) {
            playerX = laneX[currentLane]
        }
    }

    private fun showGameOverDialog() {
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("You Crashed!")
        builder.setMessage("Your score: $score")
        builder.setCancelable(false)

        builder.setPositiveButton("Play Again") { _, _ -> resetGame() }
        builder.setNegativeButton("Main Menu") { _, _ ->
            if (context is android.app.Activity) (context as android.app.Activity).finish()
        }
        builder.setNeutralButton("Save Score") { _, _ -> promptSaveScore() }

        builder.show()
    }

    private fun promptSaveScore() {
        val input = android.widget.EditText(context)
        input.filters = arrayOf(
            android.text.InputFilter.LengthFilter(4),
            android.text.InputFilter { source, _, _, _, _, _ ->
                if (source.toString().matches(Regex("[a-zA-Z]+"))) source else ""
            }
        )
        input.hint = "4-letter username"
        input.isSingleLine = true

        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("Enter your name")
        builder.setMessage("Use 4 letters, like old arcade games!")
        builder.setView(input)
        builder.setCancelable(false)

        builder.setPositiveButton("Save") { _, _ ->
            val name = input.text.toString().uppercase()
            if (name.length == 4) saveScoreToFirebase(name, score)
            else android.widget.Toast.makeText(context, "Name must be exactly 4 letters!", android.widget.Toast.LENGTH_SHORT).show()
        }

        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    private fun saveScoreToFirebase(name: String, score: Int) {
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val entry = hashMapOf("name" to name, "score" to score, "timestamp" to System.currentTimeMillis())

        db.collection("leaderboard")
            .add(entry)
            .addOnSuccessListener {
                android.widget.Toast.makeText(context, "Score saved!", android.widget.Toast.LENGTH_SHORT).show()
                if (context is android.app.Activity) (context as android.app.Activity).finish()
            }
            .addOnFailureListener {
                android.widget.Toast.makeText(context, "Failed to save score!", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    private fun resetGame() {
        currentLane = 1
        if (initialized) playerX = laneX[currentLane]
        playerY = height * 0.7f

        trafficCars.clear()
        startTime = System.currentTimeMillis()
        elapsedTime = 0L
        score = 0

        isPlaying = true
        thread = Thread(this)
        thread?.start()
    }

    // Optional: touch snaps to nearest lane (still works nicely)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!initialized) return true

        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val touchX = event.x
            var bestLane = 0
            var bestDist = Float.MAX_VALUE

            for (i in 0 until laneCount) {
                val dist = abs(touchX - (laneX[i] + playerWidth / 2f))
                if (dist < bestDist) {
                    bestDist = dist
                    bestLane = i
                }
            }
            movePlayerToLane(bestLane)
        }
        return true
    }
}
