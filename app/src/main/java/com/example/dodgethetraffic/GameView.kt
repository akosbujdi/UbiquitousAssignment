package com.example.dodgethetraffic

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class TrafficCar(var x: Float, var y: Float, val w: Float, val h: Float)

class GameView(
    context: Context,
    private val onLaneChanged: (from: Int, to: Int) -> Unit = { _, _ -> },
    private val onCrash: (score: Int) -> Unit = { _ -> }
) : SurfaceView(context), Runnable {

    private var t: Thread? = null

    // boolean variables checked throughout
    private var playing = false
    private var gameOver = false
    private val p = Paint()

    // assets to paint (background, player car, traffic)
    private val bg = BitmapFactory.decodeResource(resources, R.drawable.road)
    private val me = BitmapFactory.decodeResource(resources, R.drawable.car)
    private val car = BitmapFactory.decodeResource(resources, R.drawable.bluecar)

    private val cars = mutableListOf<TrafficCar>()
    private val laneX = FloatArray(3)
    private var lane = 1

    private var init = false
    private var px = 0f;
    private var py = 0f;
    private var pw = 0f;
    private var ph = 0f
    private var start = 0L
    private var nextSpawn = 0L

    // ---- SHIELD ----
    private val chargeNeed = 20_000L
    private val shieldDur = 5_000L
    private var charge = 0L
    private var shieldUntil = 0L
    private var lastTick = 0L

    // score count (top corner)
    private val scorePaint =
        Paint().apply { color = Color.BLACK; textSize = 72f; typeface = Typeface.DEFAULT_BOLD }
    private val barBg = Paint().apply { color = Color.argb(130, 0, 0, 0) }
    private val barFill = Paint().apply { color = Color.argb(220, 0, 200, 255) }

    // shield bar text
    private val barText =
        Paint().apply { color = Color.BLACK; textSize = 42f; typeface = Typeface.DEFAULT_BOLD }


    // glow used when shield is activated
    private val glow =
        Paint().apply { color = Color.argb(110, 0, 200, 255); style = Paint.Style.FILL }


    override fun run() {
        while (playing) {
            if (!holder.surface.isValid) continue
            val c = holder.lockCanvas()

            val now = System.currentTimeMillis()
            if (lastTick == 0L) lastTick = now
            val dt = (now - lastTick).coerceAtMost(80L)
            lastTick = now

            val w = c.width
            val h = c.height
            val elapsed = now - start
            val diff = (elapsed / 60_000f).coerceIn(0f, 1f)

            c.drawBitmap(Bitmap.createScaledBitmap(bg, w, h, false), 0f, 0f, p)

            // initialize lanes
            if (!init) {
                pw = w * 0.24f
                ph = pw * (me.height.toFloat() / me.width.toFloat())
                py = h * 0.7f

                val roadL = w * 0.08f
                val roadR = w * 0.92f
                val step = (roadR - roadL - pw) / 2f
                laneX[0] = roadL
                laneX[1] = roadL + step
                laneX[2] = roadL + step * 2f

                px = laneX[lane]
                start = now
                nextSpawn = now + 900L
                init = true
            }

            // charge shield logic
            val shieldActive = now < shieldUntil
            if (!shieldActive && charge < chargeNeed) charge =
                (charge + dt).coerceAtMost(chargeNeed)

            // spawn logic
            if (now >= nextSpawn) {
                spawn(w, diff)
                nextSpawn = now + (1500L - (900L * diff)).toLong()
                    .coerceAtLeast(520L) + Random.nextLong(-220, 260)
            }

            // increase speed of traffic over time
            val speed = h * (0.004f + 0.010f * diff)
            val it = cars.iterator()
            while (it.hasNext()) {
                val tc = it.next()
                tc.y += speed
                c.drawBitmap(
                    Bitmap.createScaledBitmap(car, tc.w.toInt(), tc.h.toInt(), true),
                    tc.x,
                    tc.y,
                    p
                )

                // remove out of screen entities
                if (tc.y > h) {
                    it.remove(); continue
                }

                // collision logic (with shield, remove car hit, without shield end game)
                if (hit(px, py, pw, ph, tc.x, tc.y, tc.w, tc.h)) {
                    if (shieldActive) {
                        it.remove(); continue
                    }
                    endGame((elapsed / 1000).toInt())
                }
            }

            // draw shield when activated
            if (shieldActive) c.drawCircle(px + pw / 2f, py + ph / 2f, max(pw, ph) * 0.75f, glow)
            c.drawBitmap(Bitmap.createScaledBitmap(me, pw.toInt(), ph.toInt(), true), px, py, p)

            // calculate score based on elapsed time
            val score = (elapsed / 1000).toInt()
            c.drawText("Score: $score", 50f, 100f, scorePaint)
            drawBar(c, w, now)

            holder.unlockCanvasAndPost(c)
        }
    }

    // end game logic, show dialog
    private fun endGame(score: Int) {
        if (gameOver) return
        gameOver = true
        playing = false
        post {
            onCrash(score)
            showGameOverDialog(score)
        }
    }

    // game over dialog
    private fun showGameOverDialog(score: Int) {
        android.app.AlertDialog.Builder(context)
            .setTitle("You Crashed!")
            .setMessage("Your score: $score")
            .setCancelable(false)
            .setPositiveButton("Play Again") { _, _ ->
                (context as? GameActivity)?.restartGameMusic()
                resetGame()
            }


            .setNegativeButton("Main Menu") { _, _ -> (context as? android.app.Activity)?.finish() }
            .show()
    }

    // reset game from game over dialog
    private fun resetGame() {
        cars.clear()
        movePlayerToLane(1)
        charge = 0L; shieldUntil = 0L; lastTick = 0L
        gameOver = false
        resume()
    }

    // spawn logic for traffic
    private fun spawn(screenW: Int, diff: Float) {
        val cw = screenW * 0.24f
        val ch = cw * (car.height.toFloat() / car.width.toFloat())
        fun add(l: Int, yOff: Float = 0f) = cars.add(TrafficCar(laneX[l], -ch + yOff, cw, ch))

        if (Random.nextFloat() < (0.60f - 0.20f * diff)) add(pickLane())
        else {
            val a = pickLane()
            var b = Random.nextInt(0, 3); while (b == a) b = Random.nextInt(0, 3)
            add(a); add(b, -(ch * 0.35f))
        }
    }

    // pick lane for oncoming traffic
    private fun pickLane(): Int {
        val r = Random.nextFloat()
        return when {
            r < 0.55f -> lane
            r < 0.85f -> (lane + if (Random.nextBoolean()) 1 else -1)
            else -> Random.nextInt(0, 3)
        }.coerceIn(0, 2)
    }

    // logic used for calculating collision (overlap)
    private fun hit(
        px: Float,
        py: Float,
        pw: Float,
        ph: Float,
        x: Float,
        y: Float,
        w: Float,
        h: Float
    ): Boolean {
        val pad = 0.2f
        val aL = px + pw * pad;
        val aR = px + pw * (1 - pad)
        val aT = py + ph * pad;
        val aB = py + ph * (1 - pad)
        val bL = x + w * pad;
        val bR = x + w * (1 - pad)
        val bT = y + h * pad;
        val bB = y + h * (1 - pad)
        return aR > bL && aL < bR && aB > bT && aT < bB
    }

    // draw shield bar on screen
    private fun drawBar(c: Canvas, screenW: Int, now: Long) {
        val active = now < shieldUntil
        val pct = charge.toFloat() / chargeNeed.toFloat()
        val L = 50f;
        val T = 180f;
        val W = screenW - 100f;
        val H = 32f
        c.drawRoundRect(L, T, L + W, T + H, 16f, 16f, barBg)
        c.drawRoundRect(L, T, L + W * pct, T + H, 16f, 16f, barFill)

        val text = when {
            active -> "SHIELD ACTIVE"
            pct >= 1f -> "SHIELD READY (A+B)"
            else -> "SHIELD ${(pct * 100).toInt()}%"
        }
        c.drawText(text, L, T - 15f, barText)
    }

    // logic handling lane switching
    fun movePlayerToLane(l: Int) {
        val from = lane
        val to = l.coerceIn(0, 2)
        if (from == to) return
        lane = to
        if (init) px = laneX[lane]
        onLaneChanged(from, to)
    }

    // user attempting to activate shield
    fun tryActivateShield() {
        val now = System.currentTimeMillis()
        if (now >= shieldUntil && charge >= chargeNeed) {
            charge = 0L; shieldUntil = now + shieldDur
        }
    }

    // user pausing the game (closing app)
    fun pause() {
        playing = false; try {
            t?.join()
        } catch (_: Exception) {
        }
    }

    // user returning to game
    fun resume() {
        if (playing) return
        playing = true
        start = System.currentTimeMillis()
        nextSpawn = start + 900L
        t = Thread(this); t?.start()
    }

    // touch logic (if used without microbit, only swiping)
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!init) return true
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val x = e.x
            var best = 0;
            var bestD = Float.MAX_VALUE
            for (i in 0..2) {
                val d = abs(x - (laneX[i] + pw / 2f))
                if (d < bestD) {
                    bestD = d; best = i
                }
            }
            movePlayerToLane(best)
        }
        return true
    }
}
