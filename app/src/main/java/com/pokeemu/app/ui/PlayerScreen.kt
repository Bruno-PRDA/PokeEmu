package com.pokeemu.app.ui

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Slider
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.border
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pokeemu.saves.GameStorage
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Surface the native side draws into. [onTouch] (DS touchscreen) receives
 * touches as fractions of the surface, which matches the video frame.
 */
/** The surface the native side currently draws into (one game screen at a time). */
@Volatile private var currentSurface: android.view.Surface? = null

@android.annotation.SuppressLint("ClickableViewAccessibility")
@Composable
fun GameSurface(
    onSurface: (android.view.Surface?) -> Unit,
    aspect: Float,
    /** The area to fit into; the surface is the largest [aspect] rectangle inside it, centred. */
    modifier: Modifier = Modifier,
    onTouch: ((Float, Float, Boolean) -> Unit)? = null,
) = BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val width = when {
        maxWidth == Dp.Infinity -> maxHeight * aspect
        maxHeight == Dp.Infinity -> maxWidth
        else -> minOf(maxWidth, maxHeight * aspect)
    }
    AndroidView(
        modifier = Modifier.size(width, width / aspect),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                if (onTouch != null) setOnTouchListener { v, e ->
                    val pressed = e.actionMasked != android.view.MotionEvent.ACTION_UP &&
                        e.actionMasked != android.view.MotionEvent.ACTION_CANCEL
                    onTouch(e.x / v.width, e.y / v.height, pressed)
                    true
                }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        currentSurface = holder.surface
                        onSurface(holder.surface)
                    }

                    // When the layout changes, a new SurfaceView can be ready before the old one
                    // is destroyed; only detach if the destroyed surface is still the one in use.
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (currentSurface === holder.surface) {
                            currentSurface = null
                            onSurface(null)
                        }
                    }
                })
            }
        },
    )
}

@Composable
fun PlayerScreen(
    onSurface: (android.view.Surface?) -> Unit,
    onButtons: (Int) -> Unit,
    fastForward: Boolean,
    onToggleFastForward: () -> Unit,
    onOpenMenu: () -> Unit,
    overlayOpen: Boolean,
    onToggleOverlay: (() -> Unit)?,
    overlay: @Composable (Modifier) -> Unit,
    aspect: Float = 3f / 2f,
    portraitPad: PadLayout = PadLayouts.portrait,
    landscapePad: PadLayout = PadLayouts.landscape,
    onTouch: ((Float, Float, Boolean) -> Unit)? = null,
    /** The user's control layouts (see [PadCustom]). */
    portraitCustom: PadCustom = PadCustom(),
    landscapeCustom: PadCustom = PadCustom(),
    /** Layout editing: controls and the screen are dragged; the game is paused meanwhile. */
    editing: Boolean = false,
    onCustom: (landscape: Boolean, PadCustom) -> Unit = { _, _ -> },
    onEditDone: () -> Unit = {},
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val toolButtons = @Composable {
        IconButton(onClick = onToggleFastForward, colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (fastForward) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
        )) { Icon(Icons.Default.FastForward, "Fast-forward") }
        if (onToggleOverlay != null) IconButton(onClick = onToggleOverlay, colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (overlayOpen) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
        )) { Icon(Icons.Default.QueryStats, "Battle info") }
        IconButton(onClick = onOpenMenu) { Icon(Icons.Default.Menu, "Menu", tint = Color.White.copy(alpha = 0.6f)) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (landscape) {
            val custom = landscapeCustom
            // The game view stays in the middle strip between the buttons: a SurfaceView is drawn
            // above other views, so anything overlapping it would hide the controls.
            Gamepad(
                custom.applyTo(landscapePad), opacity = 0.8f, onButtons = onButtons,
                editing = editing, onMove = { id, x, y -> onCustom(true, custom.moved(id, x, y)) },
            )
            val area = Modifier.align(Alignment.Center).fillMaxHeight().fillMaxWidth(0.56f)
            if (editing) ScreenPlaceholder(aspect, area, draggable = false)
            else GameSurface(onSurface, aspect, area, onTouch = onTouch)
            Row(Modifier.align(Alignment.TopCenter)) {
                if (editing) EditBar(custom.scale, { onCustom(true, custom.copy(scale = it)) }, { onCustom(true, PadCustom()) }, onEditDone)
                else toolButtons()
            }
            if (overlayOpen && !editing) overlay(Modifier.align(Alignment.TopCenter).padding(top = 48.dp).width(360.dp).heightIn(max = 280.dp))
        } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val custom = portraitCustom
            val maxOffset = maxHeight.value * 0.45f
            Column(Modifier.fillMaxSize()) {
                // How far down the user moved the screen.
                Spacer(Modifier.height(custom.screenOffsetDp.coerceAtMost(maxOffset).dp))
                // One call site, so changing the DS layout resizes the same SurfaceView instead
                // of replacing it. Tall (DS) frames are height-limited so the controls still fit.
                val tall = aspect < 1f
                Box(
                    Modifier.fillMaxWidth().then(if (tall) Modifier.fillMaxHeight(0.62f) else Modifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val area = if (tall) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
                    if (editing) {
                        ScreenPlaceholder(aspect, area, draggable = true) { dy ->
                            onCustom(false, custom.copy(screenOffsetDp = (custom.screenOffsetDp + dy).coerceIn(0f, maxOffset)))
                        }
                    } else {
                        GameSurface(onSurface, aspect, area, onTouch = onTouch)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (editing) EditBar(custom.scale, { onCustom(false, custom.copy(scale = it)) }, { onCustom(false, PadCustom()) }, onEditDone)
                    else toolButtons()
                }
                if (overlayOpen && !editing) overlay(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = 260.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Gamepad(
                        custom.applyTo(portraitPad), opacity = 1f, onButtons = onButtons,
                        editing = editing, onMove = { id, x, y -> onCustom(false, custom.moved(id, x, y)) },
                    )
                }
            }
        }
    }
}

/** Stands in for the game screen while editing the layout (the real one would draw over the edit bar). */
@Composable
private fun ScreenPlaceholder(aspect: Float, modifier: Modifier, draggable: Boolean, onDragDp: (Float) -> Unit = {}) =
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = when {
            maxWidth == Dp.Infinity -> maxHeight * aspect
            maxHeight == Dp.Infinity -> maxWidth
            else -> minOf(maxWidth, maxHeight * aspect)
        }
        val density = LocalDensity.current
        val currentOnDrag by rememberUpdatedState(onDragDp)
        Box(
            Modifier
                .size(width, width / aspect)
                .background(Color(0xFF1E2A38))
                .border(2.dp, Color(0xFF90CAF9))
                .then(
                    if (draggable) Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures { change, dy ->
                            change.consume()
                            currentOnDrag(with(density) { dy.toDp().value })
                        }
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (draggable) "Game screen\nDrag it up or down.\nDrag the buttons to move them."
                else "Game screen\nDrag the buttons to move them.",
                color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

/** Replaces the tool buttons while editing: button size, back to the default layout, and done. */
@Composable
private fun EditBar(scale: Float, onScale: (Float) -> Unit, onReset: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Size", color = Color.White, style = MaterialTheme.typography.labelLarge)
        Slider(value = scale, onValueChange = onScale, valueRange = 0.6f..1.4f, modifier = Modifier.width(120.dp).padding(horizontal = 8.dp))
        TextButton(onClick = onReset) { Text("Reset") }
        FilledTonalButton(onClick = onDone) { Text("Done") }
    }
}

@Composable
fun LoadingScreen(message: String?) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (message == null) CircularProgressIndicator()
        else Text(message, color = Color.White, modifier = Modifier.padding(32.dp))
    }
}

/** Pause menu. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PauseMenu(
    speed: Int,
    onSpeed: (Int) -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onReset: () -> Unit,
    onQuit: () -> Unit,
    onDismiss: () -> Unit,
    onCheats: () -> Unit = {},
    onEditControls: () -> Unit = {},
    onSetCover: () -> Unit = {},
    /** DS only: current screen layout (null hides the picker). */
    dsLayout: DsLayout? = null,
    onDsLayout: (DsLayout) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Resume") } },
        title = { Text("Paused") },
        text = {
            // Scrolls so every item stays reachable in landscape, where the dialog is short.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSaveState, Modifier.fillMaxWidth()) { Text("Save state") }
                FilledTonalButton(onClick = onLoadState, Modifier.fillMaxWidth()) { Text("Load state") }
                Text("Fast-forward speed", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf(2, 3, 4, 8)) {
                        FilterChip(selected = speed == s, onClick = { onSpeed(s) }, label = { Text("${s}×") })
                    }
                }
                if (dsLayout != null) {
                    Text("Screens", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (l in DsLayout.entries) {
                            FilterChip(selected = dsLayout == l, onClick = { onDsLayout(l) }, label = { Text(l.label) })
                        }
                    }
                }
                FilledTonalButton(onClick = onCheats, Modifier.fillMaxWidth()) { Text("Cheats") }
                FilledTonalButton(onClick = onEditControls, Modifier.fillMaxWidth()) { Text("Edit controls") }
                FilledTonalButton(onClick = onSetCover, Modifier.fillMaxWidth()) { Text("Use this screen as cover") }
                FilledTonalButton(onClick = onReset, Modifier.fillMaxWidth()) { Text("Reset game") }
                FilledTonalButton(onClick = onQuit, Modifier.fillMaxWidth()) { Text("Quit to library") }
            }
        },
    )
}

/** Lists save-state slots; [saving] picks between "save into" and "load from". */
@Composable
fun StateSlotsDialog(
    saving: Boolean,
    slots: List<GameStorage.StateSlot>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val bySlot = slots.associateBy { it.slot }
    val choices = if (saving) (1..GameStorage.MAX_SLOT).toList()
    else (0..GameStorage.MAX_SLOT).filter { it in bySlot }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (saving) "Save state to…" else "Load state from…") },
        text = {
            if (choices.isEmpty()) {
                Text("No save states yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(choices) { slot ->
                        val info = bySlot[slot]
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(slot) }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumbnail(info?.thumbnail)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(if (slot == GameStorage.AUTO_SLOT) "Auto (on exit)" else "Slot $slot")
                                Text(
                                    info?.let { format.format(Date(it.modifiedAt)) } ?: "Empty",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun Thumbnail(file: File?) {
    val bitmap = remember(file, file?.lastModified()) { file?.let { BitmapFactory.decodeFile(it.path) } }
    val mod = Modifier.width(96.dp).height(64.dp).background(Color.DarkGray)
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, mod, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
    } else {
        Box(mod)
    }
}

@Composable
fun ResumePrompt(onContinue: () -> Unit, onFresh: () -> Unit) {
    AlertDialog(
        onDismissRequest = onFresh,
        title = { Text("Continue where you left off?") },
        text = { Text("Load the state saved when you last quit. Choose \"Boot game\" to start from your in-game save instead.") },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onFresh) { Text("Boot game") } },
    )
}
