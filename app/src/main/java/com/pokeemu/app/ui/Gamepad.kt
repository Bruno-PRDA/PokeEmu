package com.pokeemu.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pokeemu.core.Button
import kotlin.math.atan2
import kotlin.math.hypot

/** One on-screen control. Position is a fraction of the pad area; size is in dp. */
data class PadControl(val label: String, val buttons: Int, val x: Float, val y: Float, val radius: Dp, val pill: Boolean = false)

/** Positions for the d-pad (a special control) and every button. */
data class PadLayout(val dpadX: Float, val dpadY: Float, val dpadRadius: Dp, val controls: List<PadControl>)

object PadLayouts {
    private fun controls(
        a: Pair<Float, Float>, b: Pair<Float, Float>,
        l: Pair<Float, Float>, r: Pair<Float, Float>,
        select: Pair<Float, Float>, start: Pair<Float, Float>,
    ) = listOf(
        PadControl("A", Button.A.mask, a.first, a.second, 34.dp),
        PadControl("B", Button.B.mask, b.first, b.second, 34.dp),
        PadControl("L", Button.L.mask, l.first, l.second, 26.dp, pill = true),
        PadControl("R", Button.R.mask, r.first, r.second, 26.dp, pill = true),
        PadControl("SELECT", Button.SELECT.mask, select.first, select.second, 20.dp, pill = true),
        PadControl("START", Button.START.mask, start.first, start.second, 20.dp, pill = true),
    )

    /** Controls in the area below the game screen. */
    val portrait = PadLayout(
        dpadX = 0.24f, dpadY = 0.50f, dpadRadius = 72.dp,
        controls = controls(
            a = 0.86f to 0.42f, b = 0.68f to 0.56f,
            l = 0.13f to 0.10f, r = 0.87f to 0.10f,
            select = 0.38f to 0.90f, start = 0.62f to 0.90f,
        ),
    )

    private fun dsControls(
        a: Pair<Float, Float>, b: Pair<Float, Float>, x: Pair<Float, Float>, y: Pair<Float, Float>,
        l: Pair<Float, Float>, r: Pair<Float, Float>,
        select: Pair<Float, Float>, start: Pair<Float, Float>,
    ) = listOf(
        PadControl("A", Button.A.mask, a.first, a.second, 30.dp),
        PadControl("B", Button.B.mask, b.first, b.second, 30.dp),
        PadControl("X", Button.X.mask, x.first, x.second, 30.dp),
        PadControl("Y", Button.Y.mask, y.first, y.second, 30.dp),
        PadControl("L", Button.L.mask, l.first, l.second, 26.dp, pill = true),
        PadControl("R", Button.R.mask, r.first, r.second, 26.dp, pill = true),
        PadControl("SELECT", Button.SELECT.mask, select.first, select.second, 20.dp, pill = true),
        PadControl("START", Button.START.mask, start.first, start.second, 20.dp, pill = true),
    )

    /** DS: A/B/X/Y diamond, below the (tall) screens. */
    val dsPortrait = PadLayout(
        dpadX = 0.22f, dpadY = 0.52f, dpadRadius = 64.dp,
        controls = dsControls(
            a = 0.90f to 0.52f, b = 0.76f to 0.74f, x = 0.76f to 0.30f, y = 0.62f to 0.52f,
            l = 0.13f to 0.08f, r = 0.87f to 0.08f,
            select = 0.40f to 0.93f, start = 0.60f to 0.93f,
        ),
    )

    /** DS landscape: screens in the middle, controls on both sides. */
    val dsLandscape = PadLayout(
        dpadX = 0.11f, dpadY = 0.62f, dpadRadius = 64.dp,
        controls = dsControls(
            a = 0.95f to 0.58f, b = 0.88f to 0.74f, x = 0.88f to 0.42f, y = 0.81f to 0.58f,
            l = 0.07f to 0.14f, r = 0.93f to 0.14f,
            select = 0.08f to 0.92f, start = 0.92f to 0.92f,
        ),
    )

    /** Controls overlaid on the full screen, beside the game. */
    val landscape = PadLayout(
        dpadX = 0.11f, dpadY = 0.62f, dpadRadius = 68.dp,
        controls = controls(
            a = 0.93f to 0.55f, b = 0.84f to 0.70f,
            l = 0.07f to 0.14f, r = 0.93f to 0.14f,
            select = 0.08f to 0.92f, start = 0.92f to 0.92f,
        ),
    )
}

/**
 * Multi-touch gamepad. Every pointer event re-hit-tests all fingers, so
 * sliding a thumb from B to A (or around the d-pad) works naturally.
 */
@Composable
fun Gamepad(layout: PadLayout, opacity: Float, onButtons: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val currentOnButtons by rememberUpdatedState(onButtons)
    var pressed by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val dpadCenter = Offset(layout.dpadX * w, layout.dpadY * h)
        val dpadR = with(density) { layout.dpadRadius.toPx() }
        val shapes = layout.controls.map { c ->
            Triple(c, Offset(c.x * w, c.y * h), with(density) { c.radius.toPx() })
        }

        fun hitTest(p: Offset): Int {
            val d = p - dpadCenter
            val dist = d.getDistance()
            if (dist <= dpadR * 1.25f && dist > dpadR * 0.15f) {
                // 8-way: each direction owns a 135° arc, so diagonals overlap two directions.
                val deg = Math.toDegrees(atan2(-d.y.toDouble(), d.x.toDouble())).let { if (it < 0) it + 360 else it }
                var m = 0
                if (deg < 67.5 || deg > 292.5) m = m or Button.RIGHT.mask
                if (deg in 22.5..157.5) m = m or Button.UP.mask
                if (deg in 112.5..247.5) m = m or Button.LEFT.mask
                if (deg in 202.5..337.5) m = m or Button.DOWN.mask
                return m
            }
            for ((c, center, r) in shapes) {
                val hitW = if (c.pill) r * 1.9f else r * 1.3f
                val hitH = r * 1.3f
                if (kotlin.math.abs(p.x - center.x) <= hitW && kotlin.math.abs(p.y - center.y) <= hitH &&
                    (c.pill || hypot(p.x - center.x, p.y - center.y) <= r * 1.3f)
                ) return c.buttons
            }
            return 0
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(layout, w, h) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            var mask = 0
                            for (change in event.changes) {
                                if (change.pressed) mask = mask or hitTest(change.position)
                                change.consume()
                            }
                            if (mask != pressed) {
                                if (mask and pressed.inv() != 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                pressed = mask
                                currentOnButtons(mask)
                            }
                        }
                    }
                },
        ) {
            val base = Color.White.copy(alpha = 0.22f * opacity)
            val active = Color.White.copy(alpha = 0.55f * opacity)
            val labelStyle = TextStyle(color = Color.White.copy(alpha = 0.8f * opacity), fontSize = 14.sp)

            drawDpad(dpadCenter, dpadR, pressed, base, active)
            for ((c, center, r) in shapes) {
                val color = if (pressed and c.buttons != 0) active else base
                if (c.pill) {
                    val size = Size(r * 3.2f, r * 1.4f)
                    drawRoundRect(color, center - Offset(size.width / 2, size.height / 2), size, CornerRadius(size.height / 2))
                } else {
                    drawCircle(color, r, center)
                }
                val text = textMeasurer.measure(c.label, labelStyle.copy(fontSize = if (c.pill) 11.sp else 18.sp))
                drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f))
            }
        }
    }
}

private fun DrawScope.drawDpad(center: Offset, r: Float, pressed: Int, base: Color, active: Color) {
    val arm = r * 0.36f
    fun armRect(dx: Float, dy: Float, button: Button) {
        val c = center + Offset(dx * r * 0.6f, dy * r * 0.6f)
        val size = if (dx != 0f) Size(r * 0.8f, arm * 2) else Size(arm * 2, r * 0.8f)
        drawRoundRect(
            if (pressed and button.mask != 0) active else base,
            c - Offset(size.width / 2, size.height / 2), size, CornerRadius(arm * 0.4f),
        )
    }
    armRect(0f, -1f, Button.UP)
    armRect(0f, 1f, Button.DOWN)
    armRect(-1f, 0f, Button.LEFT)
    armRect(1f, 0f, Button.RIGHT)
    drawRect(base, center - Offset(arm, arm), Size(arm * 2, arm * 2))
}
