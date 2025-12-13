package com.example.dodgethetraffic

import android.Manifest
import android.content.pm.PackageManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        gameView = GameView(this)
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
        gameView.resume()
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
    }

    override fun onStop() {
        super.onStop()
        // IMPORTANT: leaving game (back to main menu) -> clean disconnect
        safeDisconnect()
    }

    private fun handleMicrobitCommand(cmd: String) {
        if (!cmd.startsWith("LANE:", ignoreCase = true)) return
        val lane = cmd.substringAfter("LANE:").trim().toIntOrNull() ?: return

        // ignore duplicates
        if (lane == lastLane) return
        lastLane = lane

        Log.d("MicrobitBT", "Move lane -> $lane")
        gameView.movePlayerToLane(lane)
    }

    // ---------------- Permissions + Connect ----------------

    private fun requiredPerms(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun ensureBlePermissionsAndConnect() {
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BLE)
        } else {
            startScan()
        }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScan() {
        Toast.makeText(this, "Connecting to micro:bit...", Toast.LENGTH_SHORT).show()
        microbit.startScan()
    }


    private fun safeDisconnect() {
        val hasConnectPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        if (hasConnectPerm) {
            microbit.disconnect()
        }
        lastLane = null
    }

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
