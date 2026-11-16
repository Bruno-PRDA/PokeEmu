package com.pokeemu.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pokeemu.app.ui.LoadingScreen
import com.pokeemu.app.ui.PauseMenu
import com.pokeemu.app.ui.PlayerScreen
import com.pokeemu.app.ui.PokeEmuTheme
import com.pokeemu.app.ui.ResumePrompt
import com.pokeemu.app.ui.StateSlotsDialog
import com.pokeemu.core.Button
import com.pokeemu.core.EmulatorSession
import com.pokeemu.saves.GameStorage
import com.pokeemu.saves.romId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerActivity : ComponentActivity() {

    private enum class Dialog { NONE, MENU, SAVE_STATE, LOAD_STATE, RESUME_PROMPT }

    private val storage by lazy { GameStorage(filesDir) }
    private var session: EmulatorSession? = null
    private var gameId: String? = null

    private var error by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var dialog by mutableStateOf(Dialog.NONE)
    private var fastForward by mutableStateOf(false)
    private var ffSpeed by mutableStateOf(3)
    private var stateSlots by mutableStateOf<List<GameStorage.StateSlot>>(emptyList())

    private var touchButtons = 0
    private var padButtons = 0
    private var stickButtons = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ready) showDialog(if (dialog == Dialog.NONE) Dialog.MENU else Dialog.NONE) else finish()
            }
        })

        setContent {
            PokeEmuTheme {
                if (!ready) {
                    LoadingScreen(error)
                    return@PokeEmuTheme
                }
                PlayerScreen(
                    onSurface = { session?.setSurface(it) },
                    onButtons = { touchButtons = it; pushButtons() },
                    fastForward = fastForward,
                    onToggleFastForward = ::toggleFastForward,
                    onOpenMenu = { showDialog(Dialog.MENU) },
                )
                when (dialog) {
                    Dialog.MENU -> PauseMenu(
                        speed = ffSpeed,
                        onSpeed = { ffSpeed = it; if (fastForward) session?.setSpeed(it) },
                        onSaveState = { refreshSlots(); showDialog(Dialog.SAVE_STATE) },
                        onLoadState = { refreshSlots(); showDialog(Dialog.LOAD_STATE) },
                        onReset = { session?.reset(); showDialog(Dialog.NONE) },
                        onQuit = { finish() },
                        onDismiss = { showDialog(Dialog.NONE) },
                    )
                    Dialog.SAVE_STATE, Dialog.LOAD_STATE -> StateSlotsDialog(
                        saving = dialog == Dialog.SAVE_STATE,
                        slots = stateSlots,
                        onPick = { slot -> if (dialog == Dialog.SAVE_STATE) saveState(slot) else loadState(slot) },
                        onDismiss = { showDialog(Dialog.MENU) },
                    )
                    Dialog.RESUME_PROMPT -> ResumePrompt(
                        onContinue = { loadState(GameStorage.AUTO_SLOT) },
                        onFresh = { showDialog(Dialog.NONE) },
                    )
                    Dialog.NONE -> Unit
                }
            }
        }

        val uri = intent.getParcelableExtraCompat(EXTRA_URI) ?: return finish()
        lifecycleScope.launch { boot(uri) }
    }

    private suspend fun boot(uri: Uri) {
        try {
            val rom = withContext(Dispatchers.IO) { RomLibrary(this@PlayerActivity).readRom(uri) }
            val id = romId(rom)
            gameId = id
            session = withContext(Dispatchers.IO) {
                EmulatorSession.open(
                    coreLibrary = EmulatorSession.GBA_CORE,
                    systemDir = File(filesDir, "system"),
                    rom = rom,
                    saveFile = storage.saveFile(id),
                )
            }
            session!!.start()
            ready = true
            if (storage.readState(id, GameStorage.AUTO_SLOT) != null) showDialog(Dialog.RESUME_PROMPT)
        } catch (e: Exception) {
            error = "Could not start the game:\n${e.message}"
        }
    }

    // ---- pause / resume -----------------------------------------------------

    private fun showDialog(d: Dialog) {
        dialog = d
        if (d == Dialog.NONE) session?.resume() else session?.pause()
    }

    override fun onPause() {
        super.onPause()
        session?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (dialog == Dialog.NONE) session?.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        val s = session ?: return
        if (isFinishing) {
            // Remember exactly where the player was, then write the in-game save and unload.
            writeState(GameStorage.AUTO_SLOT)
            s.close()
            session = null
        }
    }

    // ---- save states --------------------------------------------------------

    private fun refreshSlots() {
        gameId?.let { stateSlots = storage.listStates(it) }
    }

    private fun writeState(slot: Int): Boolean {
        val s = session ?: return false
        val id = gameId ?: return false
        val state = s.saveState() ?: return false
        storage.writeState(id, slot, state)
        s.snapshot()?.let { bmp ->
            storage.thumbnailFile(id, slot).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return true
    }

    private fun saveState(slot: Int) {
        val ok = writeState(slot)
        toast(if (ok) "Saved to slot $slot" else "Save state failed")
        showDialog(Dialog.NONE)
    }

    private fun loadState(slot: Int) {
        val id = gameId ?: return
        val ok = storage.readState(id, slot)?.let { session?.loadState(it) } == true
        if (!ok) toast("Could not load that state")
        showDialog(Dialog.NONE)
    }

    private fun toggleFastForward() {
        fastForward = !fastForward
        session?.setSpeed(if (fastForward) ffSpeed else 1)
    }

    // ---- physical controllers ----------------------------------------------

    private fun pushButtons() = session?.setButtons(touchButtons or padButtons or stickButtons)

    private fun keyToButton(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_X -> Button.A.mask
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_Z -> Button.B.mask
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_A -> Button.L.mask
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_S -> Button.R.mask
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> Button.START.mask
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DEL -> Button.SELECT.mask
        KeyEvent.KEYCODE_DPAD_UP -> Button.UP.mask
        KeyEvent.KEYCODE_DPAD_DOWN -> Button.DOWN.mask
        KeyEvent.KEYCODE_DPAD_LEFT -> Button.LEFT.mask
        KeyEvent.KEYCODE_DPAD_RIGHT -> Button.RIGHT.mask
        else -> 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (dialog != Dialog.NONE || !ready) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleFastForward()
            return true
        }
        val bit = keyToButton(event.keyCode)
        if (bit == 0) return super.dispatchKeyEvent(event)
        padButtons = when (event.action) {
            KeyEvent.ACTION_DOWN -> padButtons or bit
            KeyEvent.ACTION_UP -> padButtons and bit.inv()
            else -> padButtons
        }
        pushButtons()
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            val x = event.getAxisValue(MotionEvent.AXIS_X) + event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y) + event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var m = 0
            if (x < -0.5f) m = m or Button.LEFT.mask
            if (x > 0.5f) m = m or Button.RIGHT.mask
            if (y < -0.5f) m = m or Button.UP.mask
            if (y > 0.5f) m = m or Button.DOWN.mask
            stickButtons = m
            pushButtons()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, uri: Uri, name: String) =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_URI, uri).putExtra(EXTRA_NAME, name)

        @Suppress("DEPRECATION")
        private fun Intent.getParcelableExtraCompat(key: String): Uri? = getParcelableExtra(key)
    }
}
