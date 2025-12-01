package com.example.dodgethetraffic

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class LeaderboardActivity : AppCompatActivity() {

    private lateinit var leaderboardContainer: LinearLayout
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leaderboard)

        leaderboardContainer = findViewById(R.id.leaderboardContainer)

        loadLeaderboard()
    }

    private fun loadLeaderboard() {

        db.collection("leaderboard")
            .orderBy("score", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(5)
            .get()
            .addOnSuccessListener { snapshot ->
                leaderboardContainer.removeViews(1, leaderboardContainer.childCount - 1)

                var rank = 1
                for (doc in snapshot) {
                    val name = doc.getString("name") ?: "----"
                    val score = doc.getLong("score") ?: 0

                    val entry = TextView(this)
                    entry.text = "$rank.  $name     —     $score"
                    entry.textSize = 24f
                    entry.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    entry.setPadding(8, 16, 8, 16)

                    when (rank) {
                        1 -> entry.setTextColor(Color.parseColor("#FFD700")) // Gold
                        2 -> entry.setTextColor(Color.parseColor("#C0C0C0")) // Silver
                        3 -> entry.setTextColor(Color.parseColor("#CD7F32")) // Bronze
                        else -> entry.setTextColor(Color.BLACK)
                    }

                    leaderboardContainer.addView(entry)
                    rank++
                }
            }
            .addOnFailureListener {
                val errorText = TextView(this)
                errorText.text = "Error loading leaderboard."
                errorText.textSize = 22f
                errorText.setTextColor(Color.RED)
                leaderboardContainer.addView(errorText)
            }
    }
}
