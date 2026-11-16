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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Menu
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

/** Surface the native side draws into. */
@Composable
fun GameSurface(onSurface: (android.view.Surface?) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.aspectRatio(3f / 2f),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        onSurface(holder.surface)

                    override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
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
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val toolButtons = @Composable {
        IconButton(onClick = onToggleFastForward, colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (fastForward) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
        )) { Icon(Icons.Default.FastForward, "Fast-forward") }
        IconButton(onClick = onOpenMenu) { Icon(Icons.Default.Menu, "Menu", tint = Color.White.copy(alpha = 0.6f)) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (landscape) {
            GameSurface(onSurface, Modifier.align(Alignment.Center).fillMaxHeight())
            Gamepad(PadLayouts.landscape, opacity = 0.8f, onButtons = onButtons)
            Row(Modifier.align(Alignment.TopCenter)) { toolButtons() }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth()) {
                    GameSurface(onSurface, Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { toolButtons() }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Gamepad(PadLayouts.portrait, opacity = 1f, onButtons = onButtons)
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
@Composable
fun PauseMenu(
    speed: Int,
    onSpeed: (Int) -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onReset: () -> Unit,
    onQuit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Resume") } },
        title = { Text("Paused") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSaveState, Modifier.fillMaxWidth()) { Text("Save state") }
                FilledTonalButton(onClick = onLoadState, Modifier.fillMaxWidth()) { Text("Load state") }
                Text("Fast-forward speed", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf(2, 3, 4, 8)) {
                        FilterChip(selected = speed == s, onClick = { onSpeed(s) }, label = { Text("${s}×") })
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
