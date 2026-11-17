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
            // The game view stays in the middle strip between the buttons: a SurfaceView is drawn
            // above other views, so anything overlapping it would hide the controls.
            Gamepad(landscapePad, opacity = 0.8f, onButtons = onButtons)
            GameSurface(onSurface, aspect, Modifier.align(Alignment.Center).fillMaxHeight().fillMaxWidth(0.56f), onTouch = onTouch)
            Row(Modifier.align(Alignment.TopCenter)) { toolButtons() }
            if (overlayOpen) overlay(Modifier.align(Alignment.TopCenter).padding(top = 48.dp).width(360.dp).heightIn(max = 280.dp))
        } else {
            Column(Modifier.fillMaxSize()) {
                // One call site, so changing the DS layout resizes the same SurfaceView instead
                // of replacing it. Tall (DS) frames are height-limited so the controls still fit.
                val tall = aspect < 1f
                Box(
                    Modifier.fillMaxWidth().then(if (tall) Modifier.fillMaxHeight(0.62f) else Modifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    GameSurface(onSurface, aspect, if (tall) Modifier.fillMaxSize() else Modifier.fillMaxWidth(), onTouch = onTouch)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { toolButtons() }
                if (overlayOpen) overlay(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = 260.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Gamepad(portraitPad, opacity = 1f, onButtons = onButtons)
                }
            }
        }
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
