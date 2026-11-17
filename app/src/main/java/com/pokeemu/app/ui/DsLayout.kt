package com.pokeemu.app.ui

import com.pokeemu.core.EmulatorSession

/**
 * DS screen arrangements, drawn by melonDS itself (so touch input follows the
 * layout). [aspect] is the width/height of the frame the core produces.
 */
enum class DsLayout(val label: String, private val coreValue: String, val aspect: Float) {
    TOP_BOTTOM("Top / touch", "top-bottom", 256f / 384f),
    BOTTOM_TOP("Touch / top", "bottom-top", 256f / 384f),
    SIDE_BY_SIDE("Side by side", "left-right", 512f / 192f),
    // Large top screen, both screens small on the right (2:1 ratio): 768 x 384.
    HYBRID("Big top screen", "hybrid-top", 768f / 384f),
    /** Top screen above, buttons below; the touch screen can be shown in its place when needed. */
    TOP_ONLY("Top + buttons", "top", 256f / 192f),
    TOUCH_ONLY("Touch only", "bottom", 256f / 192f);

    /** Sends the layout to the core; takes effect on the next frame (or at load). */
    fun apply() {
        EmulatorSession.setCoreOption("melonds_number_of_screen_layouts", "1")
        EmulatorSession.setCoreOption("melonds_screen_layout1", coreValue)
        EmulatorSession.setCoreOption("melonds_screen_gap", "0")
        EmulatorSession.setCoreOption("melonds_hybrid_ratio", "2")
        EmulatorSession.setCoreOption("melonds_hybrid_small_screen", "both")
        // melonDS draws a cursor where the screen was touched: the black dot players saw.
        EmulatorSession.setCoreOption("melonds_show_cursor", "disabled")
    }

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: TOP_BOTTOM
    }
}
