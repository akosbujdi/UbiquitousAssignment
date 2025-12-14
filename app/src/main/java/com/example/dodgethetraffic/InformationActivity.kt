package com.example.dodgethetraffic

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class InformationActivity : AppCompatActivity() {
    private val sound by lazy { Sound.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)


    }
    override fun onResume() { super.onResume(); sound.playMenuMusic() }
    override fun onPause()  { super.onPause();  sound.pauseMusic() }
}
