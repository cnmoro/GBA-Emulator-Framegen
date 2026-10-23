package com.carlo.gbafg

import android.view.Surface

/** JNI entry points (app/src/main/cpp/gba_jni.cpp). */
object Native {
    init { System.loadLibrary("gbafg") }

    // Lossless Scaling shaders
    @JvmStatic external fun extractShaders(dllPath: String, cacheDir: String): Int
    @JvmStatic external fun probeShaders(cacheDir: String): Int
    @JvmStatic external fun shadersPresent(cacheDir: String): Boolean

    // render loop (frame generation + presentation)
    @JvmStatic external fun initRenderLoop(cacheDir: String, width: Int, height: Int, multiplier: Int,
                                           flowScale: Float, performance: Boolean, fp16: Boolean,
                                           targetFpsCap: Int): Int
    @JvmStatic external fun shutdownRenderLoop()
    @JvmStatic external fun setSurface(surface: Surface?, w: Int, h: Int)
    @JvmStatic external fun setBypass(on: Boolean)
    @JvmStatic external fun setVsyncPeriodNs(ns: Long)
    @JvmStatic external fun postedFrames(): Long
    @JvmStatic external fun generatedFrames(): Long

    // emulator
    @JvmStatic external fun loadRom(romPath: String, savePath: String): Boolean
    @JvmStatic external fun unload()
    @JvmStatic external fun setVideoScale(scale: Int)
    @JvmStatic external fun start()
    @JvmStatic external fun pause(paused: Boolean)
    @JvmStatic external fun setKeys(keys: Int)
    @JvmStatic external fun setFrameRate(fps: Double)
    @JvmStatic external fun setFastForward(on: Boolean, speed: Int)
    @JvmStatic external fun setAudioEnabled(on: Boolean)
    @JvmStatic external fun saveState(path: String): Boolean
    @JvmStatic external fun loadState(path: String): Boolean
    @JvmStatic external fun reset()
    @JvmStatic external fun emuFps(): Double
    @JvmStatic external fun copyLastFrame(out: IntArray): Boolean
    @JvmStatic external fun diagnostics(): String
    @JvmStatic external fun initLog(crashPath: String, logPath: String)
    @JvmStatic external fun nativeLog(): String
    @JvmStatic external fun setForceCpuOutput(on: Boolean)
    @JvmStatic external fun setForceDxbc(on: Boolean)
    @JvmStatic external fun setDebugT(t: Float)
    @JvmStatic external fun setFp32Subdir(dir: String)
    @JvmStatic external fun setMaliFixes(robust: Boolean, serialize: Boolean)
    @JvmStatic external fun startCapture(n: Int)
    @JvmStatic external fun captureCount(): Int
    @JvmStatic external fun dumpCount(): Int
    @JvmStatic external fun dumpCapIndex(): Int
    @JvmStatic external fun dumpName(i: Int): String?
    @JvmStatic external fun dumpData(i: Int): ByteArray?
    @JvmStatic external fun captureFrame(i: Int, info: IntArray): IntArray?

    // GBA key bits (mGBA GBAKey order)
    const val KEY_A = 1 shl 0
    const val KEY_B = 1 shl 1
    const val KEY_SELECT = 1 shl 2
    const val KEY_START = 1 shl 3
    const val KEY_RIGHT = 1 shl 4
    const val KEY_LEFT = 1 shl 5
    const val KEY_UP = 1 shl 6
    const val KEY_DOWN = 1 shl 7
    const val KEY_R = 1 shl 8
    const val KEY_L = 1 shl 9
}
