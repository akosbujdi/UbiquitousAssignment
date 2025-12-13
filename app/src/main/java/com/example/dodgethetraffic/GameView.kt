package com.example.dodgethetraffic

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

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

    // --- NEW SPAWN SYSTEM ---
    private var nextSpawnAt = System.currentTimeMillis() + 1200L
    private var lastLane = 1

    private val hitboxPadding = 0.2f
    private var initialized = false

    // tweak these
    private val baseSpeedFactor = 0.015f

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
                playerWidth = screenWidth * 0.24f
                playerHeight = playerWidth * (playerBitmap.height.toFloat() / playerBitmap.width.toFloat())
                playerY = screenHeight * 0.7f

                val trafficWidth = screenWidth * 0.24f
                val roadLeft = screenWidth * 0.08f
                val roadRight = screenWidth * 0.92f

                val usable = (roadRight - roadLeft - trafficWidth)
                val step = usable / (laneCount - 1)

                for (i in 0 until laneCount) {
                    laneX[i] = roadLeft + i * step
                }

                playerX = laneX[currentLane]
                initialized = true
            }

            // -------------------------
            // Better spawn logic here ✅
            // -------------------------
            val now = System.currentTimeMillis()
            if (now >= nextSpawnAt) {
                spawnTraffic(screenWidth)
                scheduleNextSpawn()
            }

            // Difficulty: slightly faster cars over time
            val difficulty = difficulty01() // 0..1
            val trafficSpeed = screenHeight * (baseSpeedFactor + 0.010f * difficulty)

            // Move traffic + collisions
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

    // 0..1 over time (ramps over ~60s then caps)
    private fun difficulty01(): Float {
        val s = (elapsedTime / 1000f)
        return (s / 60f).coerceIn(0f, 1f)
    }

    private fun scheduleNextSpawn() {
        val d = difficulty01()

        // interval shrinks with difficulty (more intense)
        val base = 1500L
        val minInt = 520L
        val interval = (base - (900L * d)).toLong().coerceAtLeast(minInt)

        // add jitter so it’s not robotic
        val jitter = Random.nextLong(-220L, 260L)

        nextSpawnAt = System.currentTimeMillis() + max(220L, interval + jitter)
    }

    private fun spawnTraffic(screenWidth: Int) {
        val trafficWidth = screenWidth * 0.24f
        val trafficHeight = trafficWidth * (blueCarBitmap.height.toFloat() / blueCarBitmap.width.toFloat())

        val d = difficulty01()

        // pattern weights (more chaos as difficulty rises)
        val roll = Random.nextFloat()

        when {
            // mostly single early game
            roll < (0.60f - 0.20f * d) -> {
                val lane = pickLane()
                spawnCar(lane, trafficWidth, trafficHeight, yOffset = 0f)
            }

            // double spawn in 2 lanes (gets more common later)
            roll < (0.88f - 0.05f * (1f - d)) -> {
                val (a, b) = pickTwoLanes()
                // slight y offset so they don't look identical
                spawnCar(a, trafficWidth, trafficHeight, yOffset = 0f)
                spawnCar(b, trafficWidth, trafficHeight, yOffset = -(trafficHeight * 0.35f))
            }

            // “train” in same lane (staggered)
            roll < 0.95f -> {
                val lane = pickLane()
                spawnCar(lane, trafficWidth, trafficHeight, yOffset = 0f)
                spawnCar(lane, trafficWidth, trafficHeight, yOffset = -(trafficHeight * 0.85f))
            }

           
        }
    }

    private fun spawnCar(lane: Int, w: Float, h: Float, yOffset: Float) {
        trafficCars.add(
            TrafficCar(
                x = laneX[lane],
                y = -h + yOffset,
                width = w,
                height = h
            )
        )
        lastLane = lane
    }

    // makes lane choice feel less random/boring (tends to drift left/right)
    private fun pickLane(): Int {
        val roll = Random.nextFloat()
        val lane = when {
            roll < 0.55f -> lastLane                           // repeat sometimes
            roll < 0.85f -> (lastLane + if (Random.nextBoolean()) 1 else -1) // adjacent
            else -> Random.nextInt(0, laneCount)               // jump
        }.coerceIn(0, laneCount - 1)

        return lane
    }

    private fun pickTwoLanes(): Pair<Int, Int> {
        val first = pickLane()
        var second = Random.nextInt(0, laneCount)
        while (second == first) second = Random.nextInt(0, laneCount)
        return Pair(first, second)
    }

    // a simple “wave” burst pattern
    private fun pickBurstSequence(): IntArray {
        return if (Random.nextBoolean()) intArrayOf(0, 1, 2) else intArrayOf(2, 1, 0)
    }

    fun pause() {
        isPlaying = false
        try { thread?.join() } catch (_: InterruptedException) {}
    }

    fun resume() {
        isPlaying = true
        startTime = System.currentTimeMillis()
        nextSpawnAt = System.currentTimeMillis() + 900L
        thread = Thread(this)
        thread?.start()
    }

    fun movePlayerToLane(lane: Int) {
        val clamped = lane.coerceIn(0, laneCount - 1)
        currentLane = clamped
        if (initialized) playerX = laneX[currentLane]
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

        nextSpawnAt = System.currentTimeMillis() + 900L

        isPlaying = true
        thread = Thread(this)
        thread?.start()
    }

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
