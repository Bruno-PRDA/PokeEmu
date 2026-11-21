package com.pokeemu.app.ui

import android.content.Context

/**
 * The user's changes to a built-in [PadLayout]: moved controls (by label, or
 * [DPAD] for the d-pad; positions are fractions of the controls area), one size
 * factor for every control, and how far the game screen sits below the top
 * (portrait only, in dp).
 */
data class PadCustom(
    val positions: Map<String, Pair<Float, Float>> = emptyMap(),
    val scale: Float = 1f,
    val screenOffsetDp: Float = 0f,
) {
    val isDefault get() = positions.isEmpty() && scale == 1f && screenOffsetDp == 0f

    fun applyTo(base: PadLayout): PadLayout {
        val (dx, dy) = positions[DPAD] ?: (base.dpadX to base.dpadY)
        return PadLayout(
            dpadX = dx, dpadY = dy, dpadRadius = base.dpadRadius * scale,
            controls = base.controls.map { c ->
                val (x, y) = positions[c.label] ?: (c.x to c.y)
                c.copy(x = x, y = y, radius = c.radius * scale)
            },
        )
    }

    fun moved(id: String, x: Float, y: Float) = copy(positions = positions + (id to (x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f))))

    fun encode(): String = buildList {
        positions.forEach { (id, p) -> add("$id=${p.first},${p.second}") }
        add("scale=$scale")
        add("screen=$screenOffsetDp")
    }.joinToString(";")

    companion object {
        const val DPAD = "DPAD"

        fun decode(s: String?): PadCustom {
            if (s.isNullOrBlank()) return PadCustom()
            var custom = PadCustom()
            for (part in s.split(';')) {
                val (key, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                when (key) {
                    "scale" -> value.toFloatOrNull()?.let { custom = custom.copy(scale = it.coerceIn(0.5f, 1.6f)) }
                    "screen" -> value.toFloatOrNull()?.let { custom = custom.copy(screenOffsetDp = it.coerceAtLeast(0f)) }
                    else -> {
                        val xy = value.split(',').mapNotNull { it.toFloatOrNull() }
                        if (xy.size == 2) custom = custom.moved(key, xy[0], xy[1])
                    }
                }
            }
            return custom
        }
    }
}

/** Saved [PadCustom]s, one per platform and orientation (e.g. "gba_portrait"). */
class PadCustomStore(context: Context) {
    private val prefs = context.getSharedPreferences("pad_layouts", Context.MODE_PRIVATE)

    fun get(key: String) = PadCustom.decode(prefs.getString(key, null))

    fun set(key: String, custom: PadCustom) {
        prefs.edit().apply { if (custom.isDefault) remove(key) else putString(key, custom.encode()) }.apply()
    }

    companion object {
        fun key(ds: Boolean, landscape: Boolean) = (if (ds) "ds" else "gba") + if (landscape) "_landscape" else "_portrait"
    }
}
