package com.azb.recordcard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.azb.recordcard.ui.RecordCardApp
import com.azb.recordcard.ui.theme.RecordCardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = (application as RecordCardApplication).controller
        setContent {
            RecordCardTheme {
                RecordCardApp(controller)
            }
        }
    }
}
