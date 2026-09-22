package com.dongmyeong.smsautoreply

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dongmyeong.smsautoreply.data.SettingsRepository
import com.dongmyeong.smsautoreply.ui.HomeScreen
import com.dongmyeong.smsautoreply.ui.theme.SmsAutoReplyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SettingsRepository(this)
        setContent {
            SmsAutoReplyTheme {
                HomeScreen(repository = repository)
            }
        }
    }
}
