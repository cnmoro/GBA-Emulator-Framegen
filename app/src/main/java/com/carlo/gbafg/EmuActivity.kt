package com.carlo.gbafg

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Runs a ROM: SurfaceView (frames from the LSFG render loop) + on-screen controls.
 */
class EmuActivity : Activity(), SurfaceHolder.Callback, ControlsView.Listener {

    companion object {
        const val EXTRA_ROM = "rom"
    }

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var controls: ControlsView
    private lateinit var hud: TextView
    private lateinit var rom: File
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var loopRunning = false
    @Volatile private var framegenActive = false
    @Volatile private var fastForward = false
    private var dispScale = 1
    private var internalScale = 1
    @Volatile private var surfaceReady = false
    private var refreshHz = 60f
    private var menuOpen = false
    private var controlsInit = false

    // Vulkan / frame generation setup can take seconds (pipeline creation): it runs on its own
    // thread, and every render-loop call goes through it so they stay in order.
    private val gfx = Executors.newSingleThreadExecutor()
    @Volatile private var gfxGeneration = 0

    private var lastPosted = 0L
    private var saveFile: File? = null
    @Volatile private var saveStamp = 0L to 0L

    /** mirror the battery save to the user's folder whenever the game writes it */
    private fun syncSave(force: Boolean = false) {
        val f = saveFile ?: return
        Thread {
            // compare contents: the core writes through a memory map, the file time is not reliable
            val stamp = try { java.util.zip.CRC32().apply { update(f.readBytes()) }.value to f.length() } catch (_: Throwable) { return@Thread }
            if (!force && stamp == saveStamp) return@Thread
            if (SaveSync.export(this, f)) saveStamp = stamp
        }.start()
    }
    private var lastHudT = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        LogViewer.init(this)
        prefs.migrate()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        pickHighestRefreshRate()

        rom = File(intent.getStringExtra(EXTRA_ROM) ?: run { finish(); return })
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        controls = ControlsView(this, prefs)
        controls.listener = this
        controls.onEditDone = { Native.pause(false) }
        hud = TextView(this).apply {
            setTextColor(Color.argb(220, 140, 255, 140))
            textSize = 11f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            setPadding(16, 8, 16, 8)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1))
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        hideSystemBars()

        val save = File(File(filesDir, "saves").apply { mkdirs() }, rom.nameWithoutExtension + ".sav")
        saveFile = save
        if (SaveSync.importIfNewer(this, save)) Toast.makeText(this, "Save imported from your folder", Toast.LENGTH_SHORT).show()
        saveStamp = try { java.util.zip.CRC32().apply { update(save.readBytes()) }.value to save.length() } catch (_: Throwable) { 0L to 0L }
        if (!Native.loadRom(rom.absolutePath, save.absolutePath)) {
            Toast.makeText(this, "Could not open the ROM", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Native.setAudioEnabled(prefs.audio)
        Native.setFastForward(false, prefs.ffSpeed)
        // lock the emulated rate to refresh/k when that is within 1.5 % of the GBA's 59.73 fps
        val lock = (1..4).map { refreshHz.toDouble() / it }.firstOrNull { kotlin.math.abs(it - 59.7275) / 59.7275 < 0.015 }
        Native.setFrameRate(lock ?: 0.0)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) ui.post { relayout() }
        }
    }

    // ------------------------------------------------------------------
    // display / layout
    // ------------------------------------------------------------------
    private fun pickHighestRefreshRate() {
        @Suppress("DEPRECATION")
        val d = (if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val cur = d.mode
        val best = d.supportedModes.filter {
            it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
        }.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        refreshHz = best.refreshRate
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun relayout() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val portrait = h > w
        // largest integer scale of 240x160 that fits (in portrait: the upper part of the screen)
        val availH = if (portrait) (h * 0.48f).toInt() else h
        val fit = max(1, min(w / 240, availH / 160))
        val cap = prefs.maxInternalScale
        // Frame generation runs at internalScale; the picture is then scaled by an integer factor
        // (nearest). A non-integer ratio (e.g. 2x -> 5x = 2.5) turns the half-pixel steps of the
        // generated frames into uneven 2/3-pixel rows: the scenery wobbles while walking.
        internalScale = min(cap, fit)
        dispScale = max(internalScale, (fit / internalScale) * internalScale)
        val sw = 240 * dispScale
        val sh = 160 * dispScale
        // "fill" mode: same exact buffer, stretched to the largest 3:2 rect by the compositor (smooth)
        val vw: Int
        val vh: Int
        if (prefs.fillScreen) {
            val k = min(w / 240f, availH / 160f)
            vw = (240 * k).toInt(); vh = (160 * k).toInt()
        } else {
            vw = sw; vh = sh
        }
        val lp = FrameLayout.LayoutParams(vw, vh, if (portrait) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER)
        if (portrait) lp.topMargin = max(0, (availH - vh) / 2)
        surfaceView.layoutParams = lp
        surfaceView.holder.setFixedSize(sw, sh)
        if (!controlsInit || controls.portrait != portrait) {
            controls.setOrientation(portrait)
            controlsInit = true
        }
        restartRenderLoop()
    }

    private fun totalMultiplier(): Int {
        val m = prefs.multiplier
        if (m in 2..4) return m
        return if (refreshHz >= 170f) 3 else 2
    }

    private fun restartRenderLoop() {
        val scale = internalScale
        val mult = totalMultiplier()
        val gen = ++gfxGeneration
        hud.text = "Preparing video…"
        applyHudVisibility()
        gfx.execute {
            if (loopRunning) {
                Native.shutdownRenderLoop()
                loopRunning = false
            }
            if (gen != gfxGeneration) return@execute // a newer layout is already queued
            Native.setVideoScale(scale)
            val cache = File(filesDir, "spirv").apply { mkdirs() }.absolutePath
            val wantFg = prefs.frameGen && Native.shadersPresent(cache)
            Native.setForceDxbc(prefs.shaderSrc == 2)
            Native.setFp32Subdir(prefs.fp32Subdir())
            Native.setMaliFixes((prefs.gpuFixes and 1) != 0, (prefs.gpuFixes and 2) != 0)
            val rc = Native.initRenderLoop(cache, 240 * scale, 160 * scale, mult,
                prefs.flowScale, prefs.performanceMode, prefs.fp16, refreshHz.toInt())
            loopRunning = rc >= 0
            framegenActive = rc == 0 && wantFg
            Native.setVsyncPeriodNs((1e9 / refreshHz).toLong())
            Native.setBypass(!framegenActive || fastForward)
            if (surfaceReady) attachSurfaceNow()
            if (!loopRunning) ui.post {
                Toast.makeText(this, "Failed to start video (Vulkan): $rc", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyBypass() {
        gfx.execute { if (loopRunning) Native.setBypass(!framegenActive || fastForward) }
    }

    private fun attachSurface() {
        gfx.execute { attachSurfaceNow() }
    }

    /** gfx thread only */
    private fun attachSurfaceNow() {
        if (!loopRunning || !surfaceReady) return
        val s = surfaceView.holder.surface
        Native.setForceCpuOutput(prefs.forceCpuOutput)
        if (s != null && s.isValid) Native.setSurface(s, 240 * dispScale, 160 * dispScale)
    }

    // ------------------------------------------------------------------
    // SurfaceHolder
    // ------------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        attachSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        // the surface dies when this returns: detach synchronously
        try {
            gfx.submit { if (loopRunning) Native.setSurface(null, 0, 0) }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (!menuOpen && !controls.editMode) Native.start()
        ui.post(hudTick)
    }

    override fun onPause() {
        super.onPause()
        syncSave()
        Native.pause(true)
        ui.removeCallbacks(hudTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            gfx.submit {
                if (loopRunning) Native.shutdownRenderLoop()
                loopRunning = false
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        gfx.shutdown()
        Native.unload() // stops the emulation thread and flushes the battery save
        saveFile?.let { f -> Thread { SaveSync.export(applicationContext, f) }.start() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (controls.editMode) {
            controls.saveLayout()
            controls.editMode = false
            Native.pause(false)
        } else openMenu()
    }

    // physical gamepads / keyboards
    private var padKeys = 0
    private fun mapKey(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_X -> Native.KEY_A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_Z -> Native.KEY_B
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_A -> Native.KEY_L
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_S -> Native.KEY_R
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> Native.KEY_START
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DEL -> Native.KEY_SELECT
        KeyEvent.KEYCODE_DPAD_UP -> Native.KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Native.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Native.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Native.KEY_RIGHT
        else -> 0
    }

    private var touchKeys = 0
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val k = mapKey(keyCode)
        if (k == 0) return super.onKeyDown(keyCode, event)
        padKeys = padKeys or k
        Native.setKeys(touchKeys or padKeys)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val k = mapKey(keyCode)
        if (k == 0) return super.onKeyUp(keyCode, event)
        padKeys = padKeys and k.inv()
        Native.setKeys(touchKeys or padKeys)
        return true
    }

    // ------------------------------------------------------------------
    // controls
    // ------------------------------------------------------------------
    override fun onKeys(keys: Int) {
        touchKeys = keys
        Native.setKeys(touchKeys or padKeys)
    }

    override fun onFastForward(on: Boolean) {
        fastForward = on
        Native.setFastForward(on, prefs.ffSpeed)
        applyBypass()
    }

    override fun onAction(id: String) {
        when (id) {
            "save" -> quickSave()
            "load" -> quickLoad()
            "menu" -> openMenu()
        }
    }

    private fun stateFile(slot: Int) =
        File(File(filesDir, "states").apply { mkdirs() }, "${rom.nameWithoutExtension}.ss$slot")

    private fun quickSave() {
        val slot = prefs.stateSlot
        val ok = Native.saveState(stateFile(slot).absolutePath)
        Toast.makeText(this, if (ok) "State saved (slot $slot)" else "Failed to save state", Toast.LENGTH_SHORT).show()
    }

    private fun quickLoad() {
        val slot = prefs.stateSlot
        val f = stateFile(slot)
        val ok = f.exists() && Native.loadState(f.absolutePath)
        Toast.makeText(this, if (ok) "State loaded (slot $slot)" else "No state in slot $slot", Toast.LENGTH_SHORT).show()
    }

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        Native.pause(true)
        val items = arrayOf(
            "Resume",
            "State slot: ${prefs.stateSlot}",
            "Save state",
            "Load state",
            "Frame generation: " + if (prefs.frameGen) "on" else "off",
            "Frame generation settings…",
            "Fast forward speed: " + ffLabel(prefs.ffSpeed),
            "Edit button layout",
            "Sound: " + if (prefs.audio) "on" else "off",
            "On-screen info: " + prefs.hudLabel(),
            "View / copy diagnostic log",
            "Capture frames (diagnostics)",
            "Reset game",
            "Quit",
        )
        AlertDialog.Builder(this)
            .setTitle(rom.nameWithoutExtension)
            .setItems(items) { _, which ->
                menuOpen = false
                when (which) {
                    0 -> Native.pause(false)
                    1 -> pickSlot()
                    2 -> { quickSave(); Native.pause(false) }
                    3 -> { quickLoad(); Native.pause(false) }
                    4 -> { prefs.frameGen = !prefs.frameGen; restartRenderLoop(); Native.pause(false) }
                    5 -> fgSettings()
                    6 -> pickFfSpeed()
                    7 -> { controls.editMode = true }
                    8 -> { prefs.audio = !prefs.audio; Native.setAudioEnabled(prefs.audio); Native.pause(false) }
                    9 -> { prefs.hudMode = (prefs.hudMode + 1) % 3; applyHudVisibility(); Native.pause(false) }
                    10 -> showLog()
                    11 -> captureFrames()
                    12 -> { Native.reset(); Native.pause(false) }
                    13 -> finish()
                }
            }
            .setOnCancelListener { menuOpen = false; Native.pause(false) }
            .show()
    }

    /** quick A/B of the frame generation knobs without leaving the game */
    private fun fgSettings() {
        val flows = listOf(0.5f, 0.75f, 1.0f)
        val scales = listOf(2, 3, 4, 5, 6)
        val items = arrayOf(
            "Flow quality: " + when (prefs.flowScale) { 0.5f -> "light (0.5)"; 1.0f -> "maximum (1.0)"; else -> "balanced (0.75)" },
            "Performance mode (3.1P): " + if (prefs.performanceMode) "on" else "off",
            "Shaders: " + prefs.shaderLabel(),
            "Max internal scale: ${prefs.maxInternalScale}x",
            "GPU fixes: " + prefs.gpuFixesLabel(),
            "Image size: " + if (prefs.fillScreen) "fill screen (smoothed)" else "integer scale (sharp)",
        )
        AlertDialog.Builder(this).setTitle("Frame generation")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> prefs.flowScale = flows[(flows.indexOf(prefs.flowScale) + 1).mod(flows.size)]
                    1 -> prefs.performanceMode = !prefs.performanceMode
                    2 -> prefs.shaderSrc = (prefs.shaderSrc + 1) % 5
                    3 -> prefs.maxInternalScale = scales[(scales.indexOf(prefs.maxInternalScale) + 1).mod(scales.size)]
                    4 -> prefs.gpuFixes = (prefs.gpuFixes + 3) % 4
                    5 -> prefs.fillScreen = !prefs.fillScreen
                }
                if (which == 3 || which == 5) relayout() else restartRenderLoop()
                fgSettings()
            }
            .setNegativeButton("Back to game") { _, _ -> Native.pause(false) }
            .setOnCancelListener { Native.pause(false) }
            .show()
    }

    /** Records the next presented frames (real + generated, as LSFG produced them) into a zip in Downloads. */
    private fun captureFrames() {
        val opts = arrayOf("Normal capture", "Input test A (t=1)", "Input test B (t=0)")
        AlertDialog.Builder(this).setTitle("Capture frames")
            .setItems(opts) { _, which ->
                val t = when (which) { 1 -> 0.999f; 2 -> 0.001f; else -> 0f }
                Native.setDebugT(t)
                if (t > 0f) restartRenderLoop()
                Native.pause(false)
                Toast.makeText(this, "Move your character now — capturing in 3 s", Toast.LENGTH_LONG).show()
                val total = 40
                ui.postDelayed({
                    Native.startCapture(total)
                    Thread {
                        val t0 = System.currentTimeMillis()
                        while (Native.captureCount() < total && System.currentTimeMillis() - t0 < 6000) Thread.sleep(50)
                        val msg = try { "Saved to Download/" + saveCapture(if (t > 0f) "_t$t" else "") } catch (e: Throwable) { "Failed to save: $e" }
                        ui.post {
                            if (t > 0f) { Native.setDebugT(0f); restartRenderLoop() }
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }, 3000)
            }
            .setOnCancelListener { Native.pause(false) }
            .show()
    }

    private fun saveCapture(suffix: String): String {
        val n = Native.captureCount()
        if (n == 0) throw IllegalStateException("no frames captured (is frame generation on?)")
        val name = "gbafg_quadros_" + java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date()) + suffix + ".zip"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore rejected the file")
        contentResolver.openOutputStream(uri)!!.use { os ->
            java.util.zip.ZipOutputStream(os).use { zip ->
                val info = IntArray(4)
                val index = StringBuilder()
                for (i in 0 until n) {
                    val px = Native.captureFrame(i, info) ?: continue
                    val bmp = android.graphics.Bitmap.createBitmap(px, info[0], info[1], android.graphics.Bitmap.Config.ARGB_8888)
                    val kind = if (info[2] == 0) "real" else "generated${info[2]}"
                    val file = String.format(java.util.Locale.US, "%02d_%s.png", i, kind)
                    zip.putNextEntry(java.util.zip.ZipEntry(file))
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    bmp.recycle()
                    index.append(String.format(java.util.Locale.US, "%s %dx%d t=%.2fms\n", file, info[0], info[1], info[3] / 100.0))
                }
                for (i in 0 until Native.dumpCount()) {
                    val nm = Native.dumpName(i) ?: continue
                    val data = Native.dumpData(i) ?: continue
                    zip.putNextEntry(java.util.zip.ZipEntry("dump/$nm.bin"))
                    zip.write(data)
                    zip.closeEntry()
                }
                index.append("dumpCapIndex=${Native.dumpCapIndex()}\n")
                zip.putNextEntry(java.util.zip.ZipEntry("info.txt"))
                zip.write((index.toString() + "\n" + LogViewer.build(this, "refresh=${refreshHz}Hz scale=${internalScale}x->${dispScale}x")).toByteArray())
                zip.closeEntry()
            }
        }
        return name
    }

    private fun applyHudVisibility() {
        hud.visibility = if (prefs.hudMode == 0) View.GONE else View.VISIBLE
    }

    private fun showLog() {
        LogViewer.show(this, "refresh=${refreshHz}Hz scale=${internalScale}x->${dispScale}x fgActive=$framegenActive " +
            "loop=$loopRunning ff=$fastForward") { Native.pause(false) }
    }

    private fun ffLabel(s: Int) = if (s == 0) "unlimited" else "${s}x"

    private fun pickSlot() {
        val labels = Array(5) { i ->
            val f = stateFile(i + 1)
            "Slot ${i + 1}" + if (f.exists()) "  (" + java.text.DateFormat.getDateTimeInstance().format(f.lastModified()) + ")" else "  (empty)"
        }
        AlertDialog.Builder(this).setTitle("State slot")
            .setSingleChoiceItems(labels, prefs.stateSlot - 1) { d, which ->
                prefs.stateSlot = which + 1
                d.dismiss()
                Native.pause(false)
            }
            .setOnCancelListener { Native.pause(false) }
            .show()
    }

    private fun pickFfSpeed() {
        val values = intArrayOf(2, 3, 4, 6, 8, 0)
        val labels = values.map { ffLabel(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Fast forward speed")
            .setSingleChoiceItems(labels, values.indexOf(prefs.ffSpeed).coerceAtLeast(0)) { d, which ->
                prefs.ffSpeed = values[which]
                Native.setFastForward(fastForward, prefs.ffSpeed)
                d.dismiss()
                Native.pause(false)
            }
            .setOnCancelListener { Native.pause(false) }
            .show()
    }

    // ------------------------------------------------------------------
    // HUD
    // ------------------------------------------------------------------
    private val hudTick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val posted = Native.postedFrames()
            if (lastHudT != 0L) {
                val dt = (now - lastHudT) / 1e9
                val outFps = (posted - lastPosted) / dt
                applyHudVisibility()
                val mode = when {
                    fastForward -> "FF ${ffLabel(prefs.ffSpeed)}"
                    framegenActive -> "FG ${totalMultiplier()}x"
                    prefs.frameGen -> "FG unavailable"
                    else -> "FG off"
                }
                hud.text = String.format("game %.0f fps · display %.0f fps · %s · %dx→%dx",
                    Native.emuFps(), outFps, mode, internalScale, dispScale) +
                    if (prefs.hudMode == 2) "\n" + Native.diagnostics() + "\n${refreshHz.toInt()} Hz" else ""
            }
            lastPosted = posted
            lastHudT = now
            if ((now / 1_000_000_000L) % 3 == 0L) syncSave()
            ui.postDelayed(this, 1000)
        }
    }
}
