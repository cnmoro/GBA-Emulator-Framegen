package com.carlo.gbafg

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** ROM list, Lossless.dll import and settings. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var dllStatus: TextView
    private lateinit var settingsBox: LinearLayout

    private val reqRom = 1
    private val reqDll = 2

    private val romsDir by lazy { File(filesDir, "roms").apply { mkdirs() } }
    private val spirvDir by lazy { File(filesDir, "spirv").apply { mkdirs() } }

    private val accent = Color.rgb(185, 178, 232)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        LogViewer.init(this)
        prefs.migrate()
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "GBA FrameGen"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
        })
        col.addView(TextView(this).apply {
            text = "GBA emulator (mGBA) with Lossless Scaling frame generation"
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })

        col.addView(section("Games"))
        col.addView(button("Choose games folder (.gba and .sav live there)") { pickTree() })
        col.addView(button("Add a single ROM (.gba)") { pickRom() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        col.addView(section("Frame generation"))
        dllStatus = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        col.addView(dllStatus)
        col.addView(button("Import Lossless.dll") { pickDll() })
        col.addView(button("Import shader pack (.zip)") { pickPack() })

        col.addView(section("Settings"))
        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(settingsBox)

        col.addView(TextView(this).apply {
            text = "\nLossless.dll comes from your own copy of Lossless Scaling (Steam). It is read only to extract the shaders " +
                "on the device, and the copy is deleted afterwards.\nEmulation: mGBA (MPL-2.0). Frame generation: " +
                "lsfg-vk / LSFG-Android (MIT)."
            textSize = 11f
            setTextColor(Color.GRAY)
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refreshRoms()
        if (SaveSync.hasFolder(this)) thread {
            if (SaveSync.importRoms(this, romsDir) > 0) runOnUiThread { refreshRoms() }
        }
        refreshDll()
        refreshSettings()
    }

    // ------------------------------------------------------------------
    // ROMs
    // ------------------------------------------------------------------
    private fun pickRom() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, reqRom)
    }

    private fun refreshRoms() {
        list.removeAllViews()
        val roms = romsDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (roms.isEmpty()) {
            list.addView(TextView(this).apply { text = "No ROMs yet."; setPadding(0, dp(6), 0, dp(6)) })
        }
        for (f in roms) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(Button(this).apply {
                text = f.nameWithoutExtension
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { launch(f) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "✕"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setMessage("Remove ${f.name} from the list? (saves are kept)")
                        .setPositiveButton("Remover") { _, _ -> f.delete(); refreshRoms() }
                        .setNegativeButton("Cancel", null).show()
                }
            })
            list.addView(row)
        }
    }

    private fun launch(f: File) {
        f.setLastModified(System.currentTimeMillis())
        startActivity(Intent(this, EmuActivity::class.java).putExtra(EmuActivity.EXTRA_ROM, f.absolutePath))
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return "rom.gba"
    }

    // ------------------------------------------------------------------
    // Lossless.dll
    // ------------------------------------------------------------------
    private fun pickDll() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, reqDll)
    }

    private val reqPack = 4711
    private val reqTree = 4712

    private fun pickPack() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, reqPack)
    }

    /** extracts fpXXX/NNN.spv entries of a zip into the shader cache */
    private fun importPack(uri: Uri) {
        thread {
            var n = 0
            val msg = try {
                contentResolver.openInputStream(uri)!!.use { ins ->
                    java.util.zip.ZipInputStream(ins).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            val m = Regex("^(fp32[a-z]?)/(\\d+)\\.spv$").find(e.name)
                            if (m != null) {
                                val dir = File(spirvDir, m.groupValues[1]).apply { mkdirs() }
                                File(dir, m.groupValues[2] + ".spv").outputStream().use { zip.copyTo(it) }
                                n++
                            }
                        }
                    }
                }
                "$n shaders imported. Pick them in Settings → Shaders."
            } catch (t: Throwable) { "Error: ${t.message}" }
            runOnUiThread { AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show() }
        }
    }

    private fun pickTree() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), reqTree)
    }

    private fun refreshDll() {
        val ok = Native.shadersPresent(spirvDir.absolutePath)
        dllStatus.text = if (ok) "✔ Lossless Scaling shaders installed." else
            "Shaders not installed: import Lossless.dll (from the Lossless Scaling folder on your PC). " +
                "Without them games run normally, without frame generation."
        dllStatus.setTextColor(if (ok) Color.rgb(120, 220, 140) else Color.rgb(240, 190, 90))
    }

    @Suppress("DEPRECATION")
    private fun importDll(uri: Uri) {
        val pd = ProgressDialog(this).apply {
            setMessage("Extracting and testing shaders…")
            setCancelable(false)
            show()
        }
        thread {
            val dll = File(cacheDir, "Lossless.dll")
            var msg: String
            try {
                contentResolver.openInputStream(uri)!!.use { i -> dll.outputStream().use { o -> i.copyTo(o) } }
                spirvDir.deleteRecursively()
                spirvDir.mkdirs()
                val rc = Native.extractShaders(dll.absolutePath, spirvDir.absolutePath)
                msg = if (rc != 0) describe(rc) else {
                    val probe = Native.probeShaders(spirvDir.absolutePath)
                    if (probe == 0) "Shaders installed! Frame generation ready." else describe(probe)
                }
            } catch (t: Throwable) {
                msg = "Import error: ${t.message}"
            } finally {
                dll.delete()
            }
            runOnUiThread {
                pd.dismiss()
                AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
                refreshDll()
            }
        }
    }

    private fun describe(code: Int): String = when (code) {
        -1 -> "This file is not a valid Lossless.dll."
        -2 -> "This Lossless.dll lacks the required shaders. Update Lossless Scaling."
        -3 -> "Failed to translate a shader (unsupported Lossless Scaling version?)."
        -4 -> "Could not write shaders to app storage."
        -10 -> "This device does not support Vulkan 1.1+."
        -11 -> "Shader cache incomplete. Import again."
        -12 -> "This device's Vulkan driver rejected a shader (incompatible GPU?)."
        else -> "Unknown error ($code)."
    }

    // ------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------
    private fun refreshSettings() {
        settingsBox.removeAllViews()
        setting("Frame generation", if (prefs.frameGen) "On" else "Off") {
            prefs.frameGen = !prefs.frameGen; refreshSettings()
        }
        setting("Multiplier", if (prefs.multiplier == 0) "Automatic (from display refresh)" else "${prefs.multiplier}x") {
            choose("Multiplier", arrayOf("Automatic", "2x (≈120 fps)", "3x (≈180 fps)", "4x (≈240 fps)"),
                listOf(0, 2, 3, 4).indexOf(prefs.multiplier)) { prefs.multiplier = listOf(0, 2, 3, 4)[it] }
        }
        val flows = listOf(0.5f, 0.75f, 1.0f)
        setting("Flow quality", flowLabel(prefs.flowScale)) {
            choose("Flow quality", flows.map { flowLabel(it) }.toTypedArray(), flows.indexOf(prefs.flowScale)) {
                prefs.flowScale = flows[it]
            }
        }
        setting("Performance mode (LSFG 3.1P)", if (prefs.performanceMode) "On" else "Off") {
            prefs.performanceMode = !prefs.performanceMode; refreshSettings()
        }
        setting("Shaders", prefs.shaderLabel()) {
            prefs.shaderSrc = (prefs.shaderSrc + 1) % 5; refreshSettings()
        }
        setting("Max internal scale", "${prefs.maxInternalScale}x") {
            choose("Max internal scale", arrayOf("2x (lightest)", "3x", "4x", "5x", "6x (sharpest)"),
                listOf(2, 3, 4, 5, 6).indexOf(prefs.maxInternalScale)) { prefs.maxInternalScale = listOf(2, 3, 4, 5, 6)[it] }
        }
        val ffs = listOf(2, 3, 4, 6, 8, 0)
        setting("Fast forward", if (prefs.ffSpeed == 0) "unlimited" else "${prefs.ffSpeed}x") {
            choose("Fast forward", ffs.map { if (it == 0) "unlimited" else "${it}x" }.toTypedArray(),
                ffs.indexOf(prefs.ffSpeed)) { prefs.ffSpeed = ffs[it] }
        }
        setting("Image size", if (prefs.fillScreen) "Fill screen (smoothed)" else "Integer scale (sharp)") {
            prefs.fillScreen = !prefs.fillScreen; refreshSettings()
        }
        setting("Video output", if (prefs.forceCpuOutput) "CPU (compatibility)" else "Vulkan (automatic)") {
            prefs.forceCpuOutput = !prefs.forceCpuOutput; refreshSettings()
        }
        setting("Sound", if (prefs.audio) "On" else "Off") { prefs.audio = !prefs.audio; refreshSettings() }
        setting("Button vibration", if (prefs.haptics) "On" else "Off") {
            prefs.haptics = !prefs.haptics; refreshSettings()
        }
        setting("On-screen info (green text)", prefs.hudLabel().replaceFirstChar { it.uppercase() }) {
            prefs.hudMode = (prefs.hudMode + 1) % 3; refreshSettings()
        }
        setting("Saves folder (.sav)", SaveSync.folderLabel(this)?.let { "$it — tap to change" }
                ?: "App storage only — tap to choose a folder (e.g. your .gba folder)") {
            pickTree()
        }
        setting("Diagnostic log", "View and copy") { LogViewer.show(this, "(home screen)") }
        setting("Button layout", "Restore default") {
            prefs.setLayoutJson(true, null); prefs.setLayoutJson(false, null)
            Toast.makeText(this, "Layouts restored", Toast.LENGTH_SHORT).show()
        }
    }

    private fun flowLabel(f: Float) = when (f) {
        0.5f -> "Light (0.5)"
        1.0f -> "Maximum (1.0)"
        else -> "Balanced (0.75)"
    }

    private fun setting(title: String, value: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply { text = title; textSize = 15f })
        row.addView(TextView(this).apply { text = value; textSize = 13f; setTextColor(accent) })
        settingsBox.addView(row)
    }

    private fun choose(title: String, items: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(items, checked.coerceAtLeast(0)) { d, w -> onPick(w); d.dismiss(); refreshSettings() }
            .show()
    }

    // ------------------------------------------------------------------
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            reqPack -> importPack(uri)
            reqTree -> {
                try {
                    contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: Throwable) {
                }
                prefs.saveTree = uri.toString()
                refreshSettings()
                thread {
                    val roms = SaveSync.importRoms(this, romsDir)
                    val n = SaveSync.exportAll(this)
                    runOnUiThread {
                        refreshRoms()
                        Toast.makeText(this, "$roms game(s) found; $n save(s) copied to the folder", Toast.LENGTH_LONG).show()
                    }
                }
            }
            reqRom -> {
                val name = displayName(uri)
                val dst = File(romsDir, name.replace('/', '_'))
                thread {
                    val ok = try {
                        contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                        true
                    } catch (_: Throwable) {
                        false
                    }
                    runOnUiThread {
                        if (!ok) Toast.makeText(this, "Could not read the ROM", Toast.LENGTH_LONG).show()
                        else if (SaveSync.hasFolder(this)) launch(dst)
                        else AlertDialog.Builder(this)
                            .setMessage("To keep the .sav next to the ROM, Android requires you to authorize that folder once. Choose it now?")
                            .setPositiveButton("Choose folder") { _, _ -> pickTree() }
                            .setNegativeButton("Not now") { _, _ -> launch(dst) }
                            .show()
                    }
                }
            }
            reqDll -> importDll(uri)
        }
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("unused")
    private fun View.gone() { visibility = View.GONE }
}
