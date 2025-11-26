package com.rp.composeapp

import android.content.Context
import androidx.core.content.edit

/**
 * Manages saving and loading theme colors for chat bubbles.
 */
class ThemeSettingsManager(context: Context) {

    private val prefs = context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)

    // --- ▼▼▼▼▼ MODIFIED THIS BLOCK ▼▼▼▼▼ ---
    // Default colors updated for a dark theme
    private val defaultLeftBubbleColor = 0xFF36454F // Charcoal Gray
    private val defaultRightBubbleColor = 0xFF005f56 // Dark Teal Green
    // --- ▲▲▲▲▲ END OF MODIFICATION ▲▲▲▲▲ ---

    fun saveBubbleColors(leftColor: Int, rightColor: Int) {
        prefs.edit {
            putInt("left_bubble_color", leftColor)
            putInt("right_bubble_color", rightColor)
        }
    }

    fun getLeftBubbleColor(): Int {
        return prefs.getInt("left_bubble_color", defaultLeftBubbleColor.toInt())
    }

    fun getRightBubbleColor(): Int {
        return prefs.getInt("right_bubble_color", defaultRightBubbleColor.toInt())
    }
}
