package com.xmgo.music.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xmgo.music.presentation.navigation.Nav
import com.xmgo.music.presentation.theme.XmgoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XmgoTheme {
                Nav()
            }
        }
    }
}
