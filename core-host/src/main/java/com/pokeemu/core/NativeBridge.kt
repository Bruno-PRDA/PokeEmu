package com.pokeemu.core

import android.view.Surface

/** Raw JNI surface of libcorehost. Use [EmulatorSession] instead. */
internal object NativeBridge {
    init {
        System.loadLibrary("corehost")
    }

    external fun nativeCreate(coreLibrary: String, systemDir: String, saveDir: String): Boolean
    external fun nativeLoadGame(rom: ByteArray, savePath: String): Boolean
    external fun nativeStart()
    external fun nativePause()
    external fun nativeResume()
    external fun nativeDestroy()
    external fun nativeSetSurface(surface: Surface?)
    external fun nativeSetButtons(mask: Int)
    external fun nativeSetSpeed(multiplier: Int)
    external fun nativeReset()
    external fun nativeSaveState(): ByteArray?
    external fun nativeLoadState(state: ByteArray): Boolean
    external fun nativeFlushSave(): Boolean
    external fun nativeSnapshot(): IntArray?
    external fun nativeReadMemory(region: Int, offset: Long, length: Int): ByteArray?
    external fun nativeFps(): Double
}
