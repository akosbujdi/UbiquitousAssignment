package com.example.dodgethetraffic

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class GameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var microbit: Microbit

    private val BLE_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val REQ_BLE = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        // Initialize GameView inside the layout
        gameView = GameView(this)
        val root = findViewById<FrameLayout>(R.id.gameRoot)
        root.addView(gameView)

        // Create Microbit controller
        microbit = Microbit(this) { cmd ->
            handleMicrobitCommand(cmd)
        }

        ensureBLEPermissions()
    }
    private fun handleMicrobitCommand(cmd: String) {
        Log.d("GameActivity", "Microbit Command: $cmd")

        if (cmd.startsWith("SPD:")) {
            val speed = cmd.substring(4).toFloatOrNull() ?: 0f
            gameView.setHorizontalSpeed(speed)
        }
    }


    // ---------------------------------------------------------
    // BLE PERMISSIONS
    // ---------------------------------------------------------
    private fun ensureBLEPermissions() {
        val missingPermission = BLE_PERMISSIONS.any { perm ->
            ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermission) {
            ActivityCompat.requestPermissions(
                this,
                BLE_PERMISSIONS,
                REQ_BLE
            )
        } else {
            startMicrobitScan()
        }
    }


    @SuppressLint("MissingPermission")
    private fun startMicrobitScan() {
        Log.d("GameActivity", "Scanning for micro:bit…")
        microbit.startScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startMicrobitScan()
        }
    }


    // ---------------------------------------------------------
    // GAME LIFECYCLE
    // ---------------------------------------------------------
    override fun onPause() {
        super.onPause()
        gameView.pause()
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            microbit.disconnect()
        }
    }

}
