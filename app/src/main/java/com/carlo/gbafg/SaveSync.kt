package com.carlo.gbafg

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Mirrors the in-game battery saves (.sav) to a folder the user picked (Storage Access Framework),
 * e.g. the same folder as the .gba files. The emulator keeps writing its private copy; this copies it
 * out whenever it changes, and imports a newer .sav from the folder when a game starts.
 */
object SaveSync {
    private fun treeUri(ctx: Context): Uri? = Prefs(ctx).saveTree?.let { Uri.parse(it) }

    fun folderLabel(ctx: Context): String? = treeUri(ctx)?.let { uri ->
        DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "/" }
    }

    private data class Doc(val uri: Uri, val modified: Long, val size: Long)

    private fun find(ctx: Context, name: String): Doc? {
        val tree = treeUri(ctx) ?: return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name)
                    return Doc(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getLong(2), c.getLong(3))
            }
        }
        return null
    }

    private fun crc(b: ByteArray) = java.util.zip.CRC32().apply { update(b) }.value
    private fun marker(local: File) = File(local.parentFile, local.name + ".exported")

    /** before loading a game: take the folder's .sav if it was changed outside the app
     *  (i.e. it is not the copy we exported last time) */
    fun importIfNewer(ctx: Context, local: File): Boolean = try {
        val d = find(ctx, local.name)
        if (d == null || d.size <= 0) false else {
            val bytes = ctx.contentResolver.openInputStream(d.uri)!!.use { it.readBytes() }
            val theirs = crc(bytes)
            val lastExported = marker(local).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            val ours = if (local.exists()) crc(local.readBytes()) else -1L
            if (theirs != ours && theirs != lastExported) {
                if (local.exists()) local.copyTo(File(local.parentFile, local.name + ".bak"), overwrite = true)
                local.writeBytes(bytes)
                marker(local).writeText(theirs.toString())
                true
            } else false
        }
    } catch (_: Throwable) { false }

    /** copy our .sav to the folder (creates or overwrites <rom>.sav) */
    fun export(ctx: Context, local: File): Boolean = try {
        val tree = treeUri(ctx)
        if (tree == null || !local.exists() || local.length() == 0L) false else {
            val uri = find(ctx, local.name)?.uri ?: DocumentsContract.createDocument(ctx.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                "application/octet-stream", local.name)
            if (uri == null) false else {
                val bytes = local.readBytes()
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { o -> o.write(bytes) }
                marker(local).writeText(crc(bytes).toString())
                true
            }
        }
    } catch (_: Throwable) { false }

    /** copies the .gba files of the folder into the app (new or changed ones); returns how many */
    fun importRoms(ctx: Context, romsDir: File): Int {
        val tree = treeUri(ctx) ?: return 0
        var n = 0
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.lowercase().endsWith(".gba")) continue
                    val dst = File(romsDir, name.replace('/', '_'))
                    if (dst.exists() && dst.length() == c.getLong(2)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    ctx.contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                    n++
                }
            }
        } catch (_: Throwable) {
        }
        return n
    }

    fun hasFolder(ctx: Context) = treeUri(ctx) != null

    fun exportAll(ctx: Context): Int {
        val dir = File(ctx.filesDir, "saves")
        // first link of a folder: never overwrite a newer .sav that is already there
        return dir.listFiles { f -> f.name.endsWith(".sav") }?.count { f ->
            val d = try { find(ctx, f.name) } catch (_: Throwable) { null }
            if (d == null || d.modified < f.lastModified()) export(ctx, f) else false
        } ?: 0
    }
}
