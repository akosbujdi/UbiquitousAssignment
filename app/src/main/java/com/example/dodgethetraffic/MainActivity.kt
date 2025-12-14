package com.example.dodgethetraffic

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.FirebaseApp

class MainActivity : AppCompatActivity() {

    private val sound by lazy { Sound.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("MicrobitBT", "MainActivity onCreate - app started")
        FirebaseApp.initializeApp(this)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button>(R.id.playButton).setOnClickListener {
            Log.d("MicrobitBT", "Play pressed -> starting GameActivity")
            startActivity(Intent(this, GameActivity::class.java))
        }

        findViewById<Button>(R.id.leaderboardButton).setOnClickListener {
            startActivity(Intent(this, LeaderboardActivity::class.java))
        }
        findViewById<Button>(R.id.infoButton).setOnClickListener {
            startActivity(Intent(this, InformationActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        sound.playMenuMusic()
    }

    override fun onPause() {
        super.onPause()
        sound.pauseMusic()
    }
}
