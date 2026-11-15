package pl.fabiamonitor

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screen = TextView(this).apply {
            text = "FABIA MONITOR\n\nTEST URUCHOMIENIA\n\nAplikacja działa!"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(16, 24, 32))
        }

        setContentView(screen)
    }
}
