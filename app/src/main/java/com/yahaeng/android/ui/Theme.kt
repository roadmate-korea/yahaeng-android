package com.yahaeng.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

/** Figma "야행 토큰"과 같은 값. */
object Night {
    val bg = Color(0xFF0A0F1E)
    val surface = Color(0xFF141B30)
    val raised = Color(0xFF1E2742)
    val line = Color(0xFF2A3557)
    val text = Color(0xFFEEF1F8)
    val sub = Color(0xFF9AA4BF)
    val moon = Color(0xFF8FB0FF)
    val yellow = Color(0xFFF5B83D)
    val red = Color(0xFFFF5A5F)
    val stopped = Color(0xFF3DD68C)
    val rain = Color(0xFF5CC8FF)
}

/** Figma 텍스트 스타일(야행/Display 등)과 같은 크기. */
object NightType {
    val display = TextStyle(fontSize = 140.sp, lineHeight = 150.sp, fontWeight = FontWeight.Black)
    val headline = TextStyle(fontSize = 56.sp, lineHeight = 72.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(fontSize = 40.sp, lineHeight = 54.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 30.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal)
}

@Composable
fun NightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = Night.bg, surface = Night.surface, primary = Night.moon, onBackground = Night.text),
        content = content,
    )
}
