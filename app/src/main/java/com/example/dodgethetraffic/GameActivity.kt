package com.example.dodgethetraffic

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class GameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var microbit: Microbit

    private val REQ_BLE = 2001
    private var lastLane: Int? = null

    // --- Audio ---
    private var music: MediaPlayer? = null
    private var pool: SoundPool? = null
    private var sLeft = 0
    private var sRight = 0
    private var sCrash = 0
    fun restartGameMusic() {
        try {
            music?.seekTo(0)      // optional (remove if you want it to continue)
            music?.start()
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        // Music
        music = MediaPlayer.create(this, R.raw.game_music).apply {
            isLooping = true
            setVolume(0.7f, 0.7f)
        }

        // SFX
        pool = SoundPool.Builder().setMaxStreams(4).build().also { sp ->
            sLeft = sp.load(this, R.raw.left_switch, 1)
            sRight = sp.load(this, R.raw.right_switch, 1)
            sCrash = sp.load(this, R.raw.crash_music, 1)
        }

        // GameView (requires the callback patch in GameView)
        gameView = GameView(
            this,
            onLaneChanged = { from, to -> playLaneSfx(from, to) },
            onCrash = { score -> onGameCrash(score) }
        )
        findViewById<FrameLayout>(R.id.gameRoot).addView(gameView)

        microbit = Microbit(this) { cmd ->
            Log.d("MicrobitBT", "Received command: $cmd")
            runOnUiThread { handleMicrobitCommand(cmd) }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureBlePermissionsAndConnect()
    }

    override fun onResume() {
        super.onResume()
        try { if (music?.isPlaying != true) music?.start() } catch (_: Exception) {}
        gameView.resume()
    }

    override fun onPause() {
        super.onPause()
        try { music?.pause() } catch (_: Exception) {}
        gameView.pause()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStop() {
        super.onStop()
        safeDisconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { music?.stop(); music?.release() } catch (_: Exception) {}
        music = null
        try { pool?.release() } catch (_: Exception) {}
        pool = null
    }

    // ---------------- micro:bit commands ----------------

    private fun handleMicrobitCommand(cmd: String) {
        when {
            cmd.startsWith("LANE:", true) -> {
                val lane = cmd.substringAfter("LANE:").trim().toIntOrNull() ?: return

                // If lane changed, play correct SFX
                lastLane?.let { from ->
                    if (from != lane) playLaneSfx(from, lane)
                }
                lastLane = lane

                gameView.movePlayerToLane(lane)
            }

            cmd.equals("SHIELD", true) -> gameView.tryActivateShield()
        }
    }

    // ---------------- Crash handling ----------------

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun onGameCrash(score: Int) {
        playCrashSfx()
        try { music?.pause() } catch (_: Exception) {}

        // micro:bit crash icon (ONLY if you exposed sendCrashIcon() as a public function in Microbit)
        if (hasConnectPerm()) {
            try { microbit.sendCrashIcon() } catch (_: Exception) {}
        }
    }

    private fun playLaneSfx(from: Int, to: Int) {
        val id = if (to < from) sLeft else sRight
        pool?.play(id, 1f, 1f, 1, 0, 1f)
    }

    private fun playCrashSfx() {
        pool?.play(sCrash, 1f, 1f, 2, 0, 1f)
    }

    // ---------------- Permissions + Connect ----------------

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensureBlePermissionsAndConnect() {
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BLE)
        } else startScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScan() {
        Toast.makeText(this, "Connecting to micro:bit...", Toast.LENGTH_SHORT).show()
        microbit.startScan()
    }

    private fun hasConnectPerm(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun safeDisconnect() {
        if (hasConnectPerm()) {
            try { microbit.disconnect() } catch (_: Exception) {}
        }
        lastLane = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) startScan()
            else Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }
}
