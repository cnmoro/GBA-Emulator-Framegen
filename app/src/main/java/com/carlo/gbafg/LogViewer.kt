package com.carlo.gbafg

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** In-app diagnostics log (native ring log + status), with copy to clipboard. */
object LogViewer {
    fun init(ctx: Context) {
        try {
            Native.initLog(File(ctx.filesDir, "crash.txt").absolutePath, File(ctx.filesDir, "native.log").absolutePath)
        } catch (_: Throwable) {
        }
    }

    fun build(ctx: Context, extra: String): String {
        val p = Prefs(ctx)
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Throwable) { "?" }
        val crash = File(ctx.filesDir, "crash.txt").let { if (it.exists()) "\n--- last crash ---\n" + it.readText().takeLast(8000) else "" }
        return "GBA FrameGen $ver · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.HARDWARE}" + (if (Build.VERSION.SDK_INT >= 31) " · " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL else "") + "\n" +
            "fg=${p.frameGen} mult=${p.multiplier} flow=${p.flowScale} perf=${p.performanceMode} shaders=${p.shaderLabel()} gpuFixes=${p.gpuFixes} " +
            "maxScale=${p.maxInternalScale} cpuOutput=${p.forceCpuOutput} ff=${p.ffSpeed} sound=${p.audio}\n" +
            extra + "\n" + Native.diagnostics() + crash + "\n--- native log ---\n" + Native.nativeLog()
    }

    fun show(act: Activity, extra: String, onClose: () -> Unit = {}) {
        val text = try { build(act, extra) } catch (t: Throwable) { "failed to build log: $t" }
        val tv = TextView(act).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(act).apply { addView(HorizontalScrollView(act).apply { addView(tv) }) }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        AlertDialog.Builder(act)
            .setTitle("Diagnostic log")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("gbafg log", text.takeLast(200_000)))
                Toast.makeText(act, "Log copied to clipboard", Toast.LENGTH_LONG).show()
                onClose()
            }
            .setNegativeButton("Close") { _, _ -> onClose() }
            .setOnCancelListener { onClose() }
            .show()
    }
}
