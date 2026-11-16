package com.pokeemu.core

import android.graphics.Bitmap
import android.view.Surface
import java.io.File

/** libretro joypad button ids (bit positions in the button mask). */
enum class Button(val id: Int) {
    B(0), Y(1), SELECT(2), START(3), UP(4), DOWN(5), LEFT(6), RIGHT(7), A(8), X(9), L(10), R(11);

    val mask: Int get() = 1 shl id
}

/** libretro memory regions (RETRO_MEMORY_*). */
enum class MemoryRegion(val id: Int) { SAVE_RAM(0), RTC(1), SYSTEM_RAM(2), VIDEO_RAM(3) }

/**
 * A running game. Only one session can exist at a time (libretro cores are
 * process-global). All methods are safe to call from the main thread.
 */
class EmulatorSession private constructor() {

    @Volatile private var closed = false

    fun start() = NativeBridge.nativeStart()
    fun pause() = NativeBridge.nativePause()
    fun resume() = NativeBridge.nativeResume()
    fun reset() = NativeBridge.nativeReset()

    fun setSurface(surface: Surface?) = NativeBridge.nativeSetSurface(surface)
    fun setButtons(mask: Int) = NativeBridge.nativeSetButtons(mask)
    fun setSpeed(multiplier: Int) = NativeBridge.nativeSetSpeed(multiplier)

    /**
     * Touchscreen input as a fraction of the whole video frame (0..1 on each
     * axis; for DS, the frame is both screens stacked). The core maps it to the
     * touch screen.
     */
    fun setTouch(fx: Float, fy: Float, pressed: Boolean) {
        fun toRetro(f: Float) = (f.coerceIn(0f, 1f) * 0xFFFE - 0x7FFF).toInt()
        NativeBridge.nativeSetPointer(toRetro(fx), toRetro(fy), pressed)
    }

    fun saveState(): ByteArray? = NativeBridge.nativeSaveState()
    fun loadState(state: ByteArray): Boolean = NativeBridge.nativeLoadState(state)
    /** Forces the in-game save (save RAM) to disk now. */
    fun flushSave(): Boolean = NativeBridge.nativeFlushSave()
    fun readMemory(region: MemoryRegion, offset: Long, length: Int): ByteArray? =
        NativeBridge.nativeReadMemory(region.id, offset, length)
    /**
     * Reads console memory by bus address, e.g. [GBA_EWRAM] (256 KiB). Returns null
     * when the address isn't mapped. Safe to call while the game runs.
     */
    fun readBus(address: Long, length: Int): ByteArray? = NativeBridge.nativeReadBus(address, length)
    val fps: Double get() = NativeBridge.nativeFps()

    /** Current frame as a Bitmap (for save-state thumbnails), or null. */
    fun snapshot(): Bitmap? {
        val data = NativeBridge.nativeSnapshot() ?: return null
        val w = data[0]
        val h = data[1]
        return Bitmap.createBitmap(data, 2, w, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Stops emulation, writes the save file, and unloads the core. */
    fun close() {
        if (closed) return
        closed = true
        NativeBridge.nativeSetSurface(null)
        NativeBridge.nativeDestroy()
        active = null
    }

    companion object {
        const val GBA_CORE = "libmgba_libretro.so"
        const val NDS_CORE = "libmelondsds_libretro.so"
        const val GBA_EWRAM = 0x02000000L
        const val GBA_EWRAM_SIZE = 0x40000

        @Volatile var active: EmulatorSession? = null
            private set

        /**
         * Opens [coreLibrary], loads [romFile] (memory-mapped, so even 256 MB+ DS
         * ROMs stay off the Java heap) and its battery save from [saveFile]
         * (created/updated as the game saves). Throws on failure.
         */
        @Synchronized
        fun open(coreLibrary: String, systemDir: File, romFile: File, saveFile: File): EmulatorSession {
            active?.close()
            systemDir.mkdirs()
            saveFile.parentFile?.mkdirs()
            check(NativeBridge.nativeCreate(coreLibrary, systemDir.absolutePath, saveFile.parentFile!!.absolutePath)) {
                "Could not load emulator core $coreLibrary"
            }
            if (!NativeBridge.nativeLoadGame(romFile.absolutePath, saveFile.absolutePath)) {
                NativeBridge.nativeDestroy()
                throw IllegalStateException("The core could not load this ROM")
            }
            return EmulatorSession().also { active = it }
        }
    }
}
