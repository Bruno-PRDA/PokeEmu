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
import com.pokeemu.app.ui.StateSlotsDialog
import com.pokeemu.core.Button
import com.pokeemu.core.EmulatorSession
import com.pokeemu.saves.CheatStore
import com.pokeemu.app.ui.ChallengesScreen
import com.pokeemu.saves.ChallengeStore
import com.pokeemu.saves.GameStorage
import com.pokeemu.saves.Platform
import com.pokeemu.app.ui.CheatsScreen
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.pokeemu.saves.SaveManager
import com.pokeemu.saves.romId
import com.pokeemu.saves.NdsHeader
import com.pokeemu.saves.RomHeader
import com.pokeemu.app.ui.PadLayouts
import com.pokeemu.gen3.Gen3Game
import com.pokeemu.gen3.Gen3Save
import com.pokeemu.core.GameData
import com.pokeemu.core.LiveState
import com.pokeemu.gen3.LiveReader
import com.pokeemu.gen4.Gen4LiveReader
import com.pokeemu.gen4.Gen4Save
import com.pokeemu.gen3.RomData
import com.pokeemu.app.ui.DsLayout
import com.pokeemu.app.ui.PadCustomStore
import com.pokeemu.app.ui.PadCustom
import com.pokeemu.app.ui.OverlayPanel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PlayerActivity : ComponentActivity() {

    private enum class Dialog { NONE, MENU, SAVE_STATE, LOAD_STATE, CHEATS, EDIT_CONTROLS, CHALLENGES, CHEAT_SEARCH }

    private val storage by lazy { GameStorage(filesDir) }
    private var session: EmulatorSession? = null
    private var gameId: String? = null
    private var romFile: java.io.File? = null

    private var error by mutableStateOf<String?>(null)
    private var ready by mutableStateOf(false)
    private var dialog by mutableStateOf(Dialog.NONE)
    private var fastForward by mutableStateOf(false)
    private var ffSpeed by mutableStateOf(3)

    /** Settings are kept per game (suffix), with the last choice as the default for other games. */
    private val gameKey get() = "_" + (gameId ?: "")
    private fun saveSetting(block: android.content.SharedPreferences.Editor.() -> android.content.SharedPreferences.Editor) =
        prefs.edit().block().apply()
    private val prefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    private var dsLayout by mutableStateOf(DsLayout.TOP_BOTTOM)
    /** In "Top + buttons": the touch screen is shown in the top screen's place for now. */
    private var peekingTouch by mutableStateOf(false)
    /** Game volume (0..1) and mute, the same for every game. */
    private var volume by mutableStateOf(1f)
    private var muted by mutableStateOf(false)
    /** Opt-in: start from the state saved when the game was last quit, instead of booting it. */
    private var autoContinue by mutableStateOf(false)

    /** A curve so the slider's middle sounds like half as loud. */
    private fun applyVolume() { session?.setVolume(if (muted) 0f else volume * volume) }
    private val shownLayout get() = if (peekingTouch && dsLayout == DsLayout.TOP_ONLY) DsLayout.TOUCH_ONLY else dsLayout
    private val padStore by lazy { PadCustomStore(this) }
    /** The user's control layouts for this system (GBA or DS), loaded once the game type is known. */
    private var portraitCustom by mutableStateOf(PadCustom())
    private var landscapeCustom by mutableStateOf(PadCustom())
    private var stateSlots by mutableStateOf<List<GameStorage.StateSlot>>(emptyList())

    // Battle overlay
    private var overlayOpen by mutableStateOf(false)
    private var liveState by mutableStateOf<LiveState?>(null)
    private var romData by mutableStateOf<GameData?>(null)
    /** Sprites from the ROM, for the battle overlay. */
    private var sprites by mutableStateOf<com.pokeemu.core.SpriteSource?>(null)
    /** Reads the overlay state from a RAM snapshot; set once game data is ready. */
    private var liveReader: ((ByteArray) -> LiveState?)? = null
    private var liveRam: Pair<Long, Int> = EmulatorSession.GBA_EWRAM to EmulatorSession.GBA_EWRAM_SIZE
    private var isDs by mutableStateOf(false)
    private var overlayJob: Job? = null

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
                    overlayOpen = overlayOpen,
                    onToggleOverlay = if (pokemonTools) ::toggleOverlay else null,
                    overlay = { mod -> OverlayPanel(liveState, romData, mod, sprites) },
                    aspect = if (isDs) shownLayout.aspect else 3f / 2f,
                    portraitPad = if (isDs) PadLayouts.dsPortrait else PadLayouts.portrait,
                    landscapePad = if (isDs) PadLayouts.dsLandscape else PadLayouts.landscape,
                    onTouch = if (isDs) { x, y, down -> session?.setTouch(x, y, down) } else null,
                    portraitCustom = portraitCustom,
                    landscapeCustom = landscapeCustom,
                    editing = dialog == Dialog.EDIT_CONTROLS,
                    onCustom = { landscape, custom ->
                        if (landscape) landscapeCustom = custom else portraitCustom = custom
                        padStore.set(PadCustomStore.key(isDs, landscape), custom)
                    },
                    onEditDone = { showDialog(Dialog.NONE) },
                    onPeekTouch = if (isDs && dsLayout == DsLayout.TOP_ONLY) ({ peekingTouch = !peekingTouch; shownLayout.apply() }) else null,
                    peekingTouch = peekingTouch,
                )
                when (dialog) {
                    Dialog.MENU -> PauseMenu(
                        speed = ffSpeed,
                        onSpeed = {
                            ffSpeed = it
                            if (fastForward) session?.setSpeed(it)
                            saveSetting { putInt(PREF_FF_SPEED + gameKey, it).putInt(PREF_FF_SPEED, it) }
                        },
                        onSaveState = { refreshSlots(); showDialog(Dialog.SAVE_STATE) },
                        onLoadState = { refreshSlots(); showDialog(Dialog.LOAD_STATE) },
                        onQuit = { finish() },
                        onDismiss = { showDialog(Dialog.NONE) },
                        onCheats = { showDialog(Dialog.CHEATS) },
                        onCheatSearch = { showDialog(Dialog.CHEAT_SEARCH) },
                        onEditControls = { showDialog(Dialog.EDIT_CONTROLS) },
                        onSetCover = ::useScreenAsCover,
                        onChallenges = if (pokemonTools) ({ showDialog(Dialog.CHALLENGES) }) else null,
                        volume = volume,
                        muted = muted,
                        onVolume = {
                            volume = it
                            applyVolume()
                            saveSetting { putFloat(PREF_VOLUME, it) }
                        },
                        onMute = {
                            muted = it
                            applyVolume()
                            saveSetting { putBoolean(PREF_MUTED, it) }
                        },
                        autoContinue = autoContinue,
                        onAutoContinue = {
                            autoContinue = it
                            saveSetting { putBoolean(PREF_AUTO_CONTINUE, it) }
                        },
                        dsLayout = if (isDs) dsLayout else null,
                        onDsLayout = { l ->
                            dsLayout = l
                            peekingTouch = false
                            l.apply()
                            saveSetting { putString(PREF_DS_LAYOUT + gameKey, l.name).putString(PREF_DS_LAYOUT, l.name) }
                        },
                    )
                    Dialog.SAVE_STATE, Dialog.LOAD_STATE -> StateSlotsDialog(
                        saving = dialog == Dialog.SAVE_STATE,
                        slots = stateSlots,
                        onPick = { slot -> if (dialog == Dialog.SAVE_STATE) saveState(slot) else loadState(slot) },
                        onDismiss = { showDialog(Dialog.MENU) },
                    )
                    Dialog.CHEATS -> gameId?.let { id ->
                        // Its own window: the game's SurfaceView would draw over anything in this one.
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { showDialog(Dialog.MENU) },
                            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                        ) {
                            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                                CheatsScreen(
                                    title = intent.getStringExtra(EXTRA_NAME)?.substringBeforeLast('.') ?: "Game",
                                    store = CheatStore(storage, id),
                                    platform = if (isDs) Platform.NDS else Platform.GBA,
                                    onBack = { showDialog(Dialog.MENU) },
                                    onChanged = ::applyCheats,
                                    inGame = true,
                                    evolutionChanges = romFile?.takeIf { pokemonTools }?.let { file ->
                                        { EvolutionCheat.changes(this@PlayerActivity, file, id, isDs).map { it.second }.distinct() }
                                    },
                                )
                            }
                        }
                    }
                    Dialog.CHEAT_SEARCH -> androidx.compose.ui.window.Dialog(
                        // Own window, like Cheats: the game's SurfaceView would draw over this one.
                        onDismissRequest = { showDialog(Dialog.MENU) },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                    ) {
                        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                            com.pokeemu.app.ui.CheatSearchScreen(cheatSearchHost, onBack = { showDialog(Dialog.NONE) })
                        }
                    }
                    Dialog.CHALLENGES -> gameId?.let { id ->
                        // Own window, like Cheats: the game's SurfaceView would draw over this one.
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { showDialog(Dialog.MENU) },
                            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                        ) {
                            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                                ChallengesScreen(
                                    title = intent.getStringExtra(EXTRA_NAME)?.substringBeforeLast('.') ?: "Game",
                                    store = ChallengeStore(storage, id),
                                    onBack = { showDialog(Dialog.MENU) },
                                    onChanged = { challengeRunner?.refresh() },
                                )
                            }
                        }
                    }
                    Dialog.NONE, Dialog.EDIT_CONTROLS -> Unit
                }
            }
        }

        val uri = intent.getParcelableExtraCompat(EXTRA_URI) ?: return finish()
        lifecycleScope.launch { boot(uri) }
    }

    private suspend fun boot(uri: Uri) {
        try {
            val local = withContext(Dispatchers.IO) { RomLibrary(this@PlayerActivity).localCopy(uri) }
            val id = local.id
            gameId = id
            ffSpeed = prefs.getInt(PREF_FF_SPEED + gameKey, prefs.getInt(PREF_FF_SPEED, 3))
            val header = withContext(Dispatchers.IO) {
                local.file.inputStream().use { input -> ByteArray(NdsHeader.HEADER_SIZE).also { input.read(it) } }
            }
            val ndsHeader = NdsHeader.parse(header)
            isDs = ndsHeader != null
            // Overlay, challenges and built-in cheats read the game's memory: GBA and Gen 4 only.
            pokemonTools = ndsHeader?.liveTools ?: true
            portraitCustom = padStore.get(PadCustomStore.key(isDs, landscape = false))
            landscapeCustom = padStore.get(PadCustomStore.key(isDs, landscape = true))
            if (isDs) {
                // This game's own choice, else the last one made in any game.
                dsLayout = DsLayout.fromName(prefs.getString(PREF_DS_LAYOUT + gameKey, null) ?: prefs.getString(PREF_DS_LAYOUT, null))
                dsLayout.apply() // before load, so the first frame already uses it
            }
            // Daily safety net before the game can touch the save.
            withContext(Dispatchers.IO) { runCatching { SaveManager(storage, id).dailyBackupIfDue() } }
            romFile = local.file
            // Built-in cheat: evolution data changed in memory only (the ROM file is untouched).
            val romPatches = if (pokemonTools && CheatStore(storage, id).evolveWithoutTrading) {
                runCatching { EvolutionCheat.changes(this@PlayerActivity, local.file, id, isDs).map { (fix, _) -> fix.offset to fix.replacement } }
                    .getOrDefault(emptyList())
            } else emptyList()
            session = withContext(Dispatchers.IO) {
                EmulatorSession.open(
                    romPatches = romPatches,
                    coreLibrary = if (isDs) EmulatorSession.NDS_CORE else EmulatorSession.GBA_CORE,
                    systemDir = File(filesDir, "system"),
                    romFile = local.file,
                    saveFile = storage.saveFile(id),
                )
            }
            if (romPatches.isNotEmpty()) {
                // GBA cartridge ROM is on the bus at 0x08000000: confirm the running game sees the change.
                val (offset, bytes) = romPatches.first()
                val seen = if (isDs) null else session?.readBus(0x08000000L + offset, bytes.size)
                android.util.Log.i("PokeEmu", "Evolve without trading: ${romPatches.size} changes applied in memory" +
                    if (seen != null) ", visible to the game: ${seen.contentEquals(bytes)}" else "")
            }
            applyCheats() // before the first frame
            volume = prefs.getFloat(PREF_VOLUME, 1f)
            muted = prefs.getBoolean(PREF_MUTED, false)
            applyVolume()
            startPins(id)
            if (pokemonTools) {
                val gen = ndsHeader?.pokemonGen ?: 3
                lifecycleScope.launch(Dispatchers.IO) { places = RomDataStore(storage).locations(id, local.file, gen) }
                startChallenges(id)
            }
            session!!.start()
            ready = true
            // The game boots normally; with the option on, it picks up where it was quit instead.
            autoContinue = prefs.getBoolean(PREF_AUTO_CONTINUE, false)
            if (autoContinue && storage.readState(id, GameStorage.AUTO_SLOT) != null) loadState(GameStorage.AUTO_SLOT)
            if (!pokemonTools) Unit
            else if (ndsHeader?.pokemonGen == 5) prepareGen5Overlay(id, local.file)
            else if (isDs) prepareDsOverlay(id, local.file)
            else prepareOverlay(id, withContext(Dispatchers.IO) { local.file.readBytes() }) // GBA: <= 32 MB
        } catch (e: Exception) {
            error = "Could not start the game:\n${e.message}"
        }
    }

    // ---- battle overlay -----------------------------------------------------

    /** Loads game data (cached ROM scan) and the player's trainer ID for the RAM reader. */
    private fun prepareOverlay(id: String, rom: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            val store = RomDataStore(storage)
            val data = store.cached(id) ?: runCatching { store.scanAndCache(id, rom) }.getOrNull()
            val save = runCatching { Gen3Save.parse(storage.saveFile(id).readBytes()) }.getOrNull()
            val code = RomHeader.parse(rom)?.gameCode.orEmpty()
            val game = save?.game ?: when {
                code.startsWith("BPR") || code.startsWith("BPG") -> Gen3Game.FIRERED_LEAFGREEN
                code.startsWith("BPE") -> Gen3Game.EMERALD
                code.startsWith("AXV") || code.startsWith("AXP") -> Gen3Game.RUBY_SAPPHIRE
                else -> Gen3Game.FIRERED_LEAFGREEN
            }
            val otId = save?.trainer?.let { (it.secretId.toLong() shl 16) or it.trainerId.toLong() }
            val gbaSprites = data?.sprites?.takeIf { it.hasPokemon }?.let { tables ->
                com.pokeemu.gen3.Gen3Sprites(tables) { o, n -> if (o in rom.indices) rom.copyOfRange(o, minOf(rom.size, o + n)) else null }
            }
            withContext(Dispatchers.Main) {
                romData = data
                sprites = gbaSprites
                val reader = LiveReader(
                    playerOtId = otId,
                    game = game,
                    encoding = save?.pkEncoding,
                    speciesCount = data?.speciesNames?.size?.takeIf { it > 0 } ?: 2048,
                    moveCount = data?.moveNames?.size?.takeIf { it > 0 } ?: 1024,
                )
                // IWRAM holds gMain, whose inBattle flag hides the opponent once a battle ends.
                liveReader = { ram -> reader.read(ram, session?.readBus(GBA_IWRAM, GBA_IWRAM_SIZE)) }
            }
        }
    }

    /** DS: names/base stats from the ROM, trainer IDs from the save, parties from main RAM. */
    private fun prepareDsOverlay(id: String, romFile: java.io.File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val data = RomDataStore(storage).gen4(id, romFile)
            val trainer = runCatching { Gen4Save.parse(storage.saveFile(id).readBytes()).trainer }.getOrNull()
            val reader = Gen4LiveReader(
                trainerId = trainer?.trainerId,
                secretId = trainer?.secretId,
                speciesCount = data?.speciesNames?.size?.takeIf { it > 0 } ?: 2048,
                moveCount = data?.moveNames?.size?.takeIf { it > 0 } ?: 1024,
            )
            val dsSprites = runCatching { com.pokeemu.gen4.Gen4Sprites.open(com.pokeemu.gen4.FileRomSource(romFile)) }.getOrNull()
            withContext(Dispatchers.Main) {
                romData = data
                sprites = dsSprites
                liveRam = Gen4LiveReader.RAM_ADDRESS to Gen4LiveReader.RAM_SIZE
                liveReader = reader::read
            }
        }
    }

    private fun prepareGen5Overlay(id: String, romFile: java.io.File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val data = RomDataStore(storage).gen5(id, romFile)
            val trainer = runCatching { com.pokeemu.gen5.Gen5Save.parse(storage.saveFile(id).readBytes()).trainer }.getOrNull()
            val reader = com.pokeemu.gen5.Gen5LiveReader(
                trainerId = trainer?.trainerId,
                secretId = trainer?.secretId,
                speciesCount = data?.speciesNames?.size?.takeIf { it > 0 } ?: 2048,
                moveCount = data?.moveNames?.size?.takeIf { it > 0 } ?: 1024,
            )
            val gen5Sprites = runCatching { com.pokeemu.gen5.Gen5Sprites.open(com.pokeemu.gen4.FileRomSource(romFile)) }.getOrNull()
            withContext(Dispatchers.Main) {
                romData = data
                sprites = gen5Sprites
                liveRam = com.pokeemu.gen5.Gen5LiveReader.RAM_ADDRESS to com.pokeemu.gen5.Gen5LiveReader.RAM_SIZE
                liveReader = reader::read
            }
        }
    }

    // ---- cheat search and frozen values -------------------------------------------

    private var cheatSearch: CheatSearch? = null
    /** Frozen values of this game, written while it runs. */
    @Volatile private var pins: List<com.pokeemu.saves.PinStore.Pin> = emptyList()

    /** The game's work RAM: GBA EWRAM and IWRAM, or the DS main RAM. */
    private fun searchRegions(): List<CheatSearch.Region> =
        if (isDs) listOf(CheatSearch.Region(0x02000000L, 0x400000))
        else listOf(CheatSearch.Region(EmulatorSession.GBA_EWRAM, EmulatorSession.GBA_EWRAM_SIZE), CheatSearch.Region(GBA_IWRAM, GBA_IWRAM_SIZE))

    private val cheatSearchHost = object : com.pokeemu.app.ui.CheatSearchHost {
        private fun read(r: CheatSearch.Region) = session?.readBus(r.base, r.length)
        private fun store() = gameId?.let { com.pokeemu.saves.PinStore(storage, it) }
        override val search get() = cheatSearch
        override fun newSearch(size: Int): Boolean {
            val s = CheatSearch(searchRegions(), size)
            return s.start(::read).also { if (it) cheatSearch = s }
        }
        override fun filter(compare: CheatSearch.Compare, value: Long?) = cheatSearch?.filter(::read, compare, value) == true
        override fun results() = cheatSearch?.results(::read).orEmpty()
        override fun setOnce(address: Long, size: Int, value: Long) =
            session?.writeBus(address, ByteArray(size) { (value shr (8 * it)).toByte() }) == true
        override val pins get() = this@PlayerActivity.pins
        override fun putPin(pin: com.pokeemu.saves.PinStore.Pin) { store()?.let { it.put(pin); this@PlayerActivity.pins = it.list() } }
        override fun removePin(address: Long) { store()?.let { it.remove(address); this@PlayerActivity.pins = it.list() } }
    }

    /** Keeps frozen values written while the game runs. */
    private fun startPins(id: String) {
        pins = com.pokeemu.saves.PinStore(storage, id).list()
        lifecycleScope.launch {
            while (isActive) {
                delay(PIN_REFRESH_MS)
                if (dialog != Dialog.NONE) continue
                val s = session ?: continue
                for (p in pins) if (p.enabled) s.writeBus(p.address, ByteArray(p.size) { (p.value shr (8 * it)).toByte() })
            }
        }
    }

    private var challengeRunner: ChallengeRunner? = null
    /** Location number -> place name for this game (Nuzlocke areas); loaded in the background. */
    @Volatile private var places: Map<Int, String> = emptyMap()

    /** False for DS games the Pokemon tools don't understand (Black, White...): they only play. */
    private var pokemonTools by mutableStateOf(true)

    /**
     * Challenge modes (shiny odds, Nuzlocke) watch the game's memory a few
     * times a second while it runs, and may change it; never the ROM or save file.
     */
    private fun startChallenges(id: String) {
        val runner = ChallengeRunner(
            ChallengeStore(storage, id),
            data = { romData },
            write = { offset, bytes -> session?.writeBus(liveRam.first + offset, bytes) == true },
            notify = { msg -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show() },
            places = { places },
        )
        challengeRunner = runner
        lifecycleScope.launch {
            while (isActive) {
                delay(CHALLENGE_REFRESH_MS)
                if (!runner.active || dialog != Dialog.NONE) continue
                val s = session ?: continue
                val reader = liveReader ?: continue
                val ram = withContext(Dispatchers.IO) { s.readBus(liveRam.first, liveRam.second) } ?: continue
                val state = withContext(Dispatchers.Default) { reader(ram) } ?: continue
                runner.onState(state, ram)
            }
        }
    }

    private fun toggleOverlay() {
        overlayOpen = !overlayOpen
        overlayJob?.cancel()
        if (!overlayOpen) return
        overlayJob = lifecycleScope.launch {
            while (isActive) {
                val s = session
                val reader = liveReader
                if (s != null && reader != null) {
                    liveState = withContext(Dispatchers.Default) {
                        s.readBus(liveRam.first, liveRam.second)?.let(reader)
                    }
                }
                delay(OVERLAY_REFRESH_MS)
            }
        }
    }

    // ---- pause / resume -----------------------------------------------------

    /** Sends this game's enabled cheats to the core (replacing what it had). */
    private fun applyCheats() {
        val id = gameId ?: return
        val s = session ?: return
        val codes = CheatStore(storage, id).enabledCodes()
        if (!s.setCheats(codes) && codes.isNotEmpty()) {
            android.widget.Toast.makeText(this, "This emulator core can't run cheats", android.widget.Toast.LENGTH_LONG).show()
        }
    }

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
        overlayJob?.cancel()
        if (isFinishing) {
            // Remember exactly where the player was, then write the in-game save and unload.
            writeState(GameStorage.AUTO_SLOT)
            s.close()
            session = null
        }
    }

    // ---- save states --------------------------------------------------------

    /** The current frame becomes the game's picture in the main menu (e.g. its title screen). */
    private fun useScreenAsCover() {
        val id = gameId ?: return
        val frame = session?.snapshot()
        if (frame == null) {
            android.widget.Toast.makeText(this, "Couldn't capture the screen", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val file = storage.coverFile(id)
            val tmp = java.io.File(file.parentFile, "cover.tmp")
            tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(this@PlayerActivity, "Cover set: this screen now shows in the main menu", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        KeyEvent.KEYCODE_BUTTON_X -> Button.X.mask
        KeyEvent.KEYCODE_BUTTON_Y -> Button.Y.mask
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
        private const val GBA_IWRAM = 0x03000000L
        private const val GBA_IWRAM_SIZE = 0x8000
        private const val PREF_DS_LAYOUT = "ds_layout"
        private const val PREF_FF_SPEED = "ff_speed"
        private const val CHALLENGE_REFRESH_MS = 300L
        private const val PREF_VOLUME = "volume"
        private const val PREF_MUTED = "muted"
        private const val PREF_AUTO_CONTINUE = "auto_continue"
        private const val PIN_REFRESH_MS = 150L
        private const val EXTRA_URI = "uri"
        private const val OVERLAY_REFRESH_MS = 500L
        private const val EXTRA_NAME = "name"

        fun intent(context: Context, uri: Uri, name: String) =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_URI, uri).putExtra(EXTRA_NAME, name)

        @Suppress("DEPRECATION")
        private fun Intent.getParcelableExtraCompat(key: String): Uri? = getParcelableExtra(key)
    }
}
