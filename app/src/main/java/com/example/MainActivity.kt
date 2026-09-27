package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.LumiCockpitScreen
import com.example.ui.LumiViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LumiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                LumiCockpitScreen(viewModel = viewModel)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Failsafe stop robot if app is minimized or loses focus
        viewModel.emergencyStop()
    }

    override fun onStop() {
        super.onStop()
        viewModel.emergencyStop()
    }
}
