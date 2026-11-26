package com.example.remind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App language: romanized Hinglish (default) or English. Backed by Compose state,
 * so every tr() read inside a composable recomposes when the setting flips.
 */
object Lang {
    const val PREFS = "settings"
    const val KEY_ENGLISH = "english_ui"

    var english by mutableStateOf(false)
}

fun tr(hinglish: String, english: String): String = if (Lang.english) english else hinglish
