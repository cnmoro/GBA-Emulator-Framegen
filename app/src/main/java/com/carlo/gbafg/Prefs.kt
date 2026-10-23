package com.carlo.gbafg

import android.content.Context
import android.content.SharedPreferences

/** User settings. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var frameGen: Boolean
        get() = p.getBoolean("frameGen", true)
        set(v) = p.edit().putBoolean("frameGen", v).apply()

    /** 0 = automatic (from the display refresh rate), else total multiplier 2..4 */
    var multiplier: Int
        get() = p.getInt("multiplier", 0)
        set(v) = p.edit().putInt("multiplier", v).apply()

    var flowScale: Float
        get() = p.getFloat("flowScale", 0.75f)
        set(v) = p.edit().putFloat("flowScale", v).apply()

    var performanceMode: Boolean
        get() = p.getBoolean("performance", true)
        set(v) = p.edit().putBoolean("performance", v).apply()

    /** shader source: 0 = FP32 SPIR-V, 1 = FP16, 2 = DXBC, 3 = FP32 recompiled, 4 = FP32 recompiled+optimized */
    var shaderSrc: Int
        get() = p.getInt("shaderSrc", if (p.getBoolean("fp16", false)) 1 else 0)
        set(v) = p.edit().putInt("shaderSrc", v).apply()

    val fp16: Boolean get() = shaderSrc == 1

    fun shaderLabel() = when (shaderSrc) { 1 -> "FP16"; 2 -> "DXBC (translated)"; 3 -> "FP32 recompiled"; 4 -> "FP32 recompiled+optimized"; else -> "FP32" }
    fun fp32Subdir() = when (shaderSrc) { 3 -> "fp32r"; 4 -> "fp32o"; else -> "fp32" }

    /** maximum internal (frame generation) scale of the 240x160 picture */
    var maxInternalScale: Int
        get() = p.getInt("maxInternalScale", 2)
        set(v) = p.edit().putInt("maxInternalScale", v).apply()

    /** fast forward speed: 2..8, 0 = unlimited */
    var ffSpeed: Int
        get() = p.getInt("ffSpeed", 3)
        set(v) = p.edit().putInt("ffSpeed", v).apply()

    var audio: Boolean
        get() = p.getBoolean("audio", true)
        set(v) = p.edit().putBoolean("audio", v).apply()

    /** green text at the top of the game screen: 0 = hidden, 1 = FPS only, 2 = FPS + diagnostics */
    var hudMode: Int
        get() = p.getInt("hudMode", 0)
        set(v) = p.edit().putInt("hudMode", v).apply()

    fun hudLabel() = when (hudMode) { 1 -> "FPS only"; 2 -> "FPS + diagnostics"; else -> "hidden" }

    var showFps: Boolean
        get() = p.getBoolean("showFps", true)
        set(v) = p.edit().putBoolean("showFps", v).apply()

    var showDiag: Boolean
        get() = p.getBoolean("showDiag", true)
        set(v) = p.edit().putBoolean("showDiag", v).apply()

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    /** present through the CPU (ANativeWindow_lock) instead of a Vulkan swapchain */
    var forceCpuOutput: Boolean
        get() = p.getBoolean("forceCpuOutput", false)
        set(v) = p.edit().putBoolean("forceCpuOutput", v).apply()

    /** 0.1.3: earlier builds were silent (mGBA volume bug), so people turned sound off; turn it back on once */
    fun migrate() {
        if (!p.getBoolean("audioFix013", false)) p.edit().putBoolean("audioFix013", true).putBoolean("audio", true).apply()
    }

    /** stretch the (exactly scaled) picture to fill the screen, smoothed by the compositor */
    var fillScreen: Boolean
        get() = p.getBoolean("fillScreen", false)
        set(v) = p.edit().putBoolean("fillScreen", v).apply()

    /** GPU workarounds: bit 0 = robust image access, bit 1 = barrier after every dispatch */
    var gpuFixes: Int
        get() = p.getInt("gpuFixes", 1)
        set(v) = p.edit().putInt("gpuFixes", v).apply()

    fun gpuFixesLabel() = when (gpuFixes) { 3 -> "robustness + sync"; 1 -> "robustness only"; 2 -> "sync only"; else -> "none" }

    /** SAF tree uri of the folder that mirrors the .sav files (null = only inside the app) */
    var saveTree: String?
        get() = p.getString("saveTree", null)
        set(v) = p.edit().putString("saveTree", v).apply()

    var stateSlot: Int
        get() = p.getInt("stateSlot", 1)
        set(v) = p.edit().putInt("stateSlot", v).apply()

    fun layoutJson(portrait: Boolean): String? = p.getString(if (portrait) "layoutP" else "layoutL", null)
    fun setLayoutJson(portrait: Boolean, json: String?) =
        p.edit().putString(if (portrait) "layoutP" else "layoutL", json).apply()
}
