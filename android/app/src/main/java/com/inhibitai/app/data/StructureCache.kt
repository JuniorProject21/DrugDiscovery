package com.inhibitai.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The molecule drawings, kept on the device as the PNGs the server sent.
 *
 * Without this every thumbnail is its own request to `/chem/depict`: a history of twenty
 * compounds is twenty round trips on every cold start, and offline history shows empty frames
 * where the structures should be. The drawing for a given structure, size and theme never
 * changes, so it is worth keeping.
 *
 * It lives in filesDir rather than cacheDir on purpose: this backs an offline feature, and
 * Android empties cacheDir whenever it feels short of space. [maxBytes] and the eviction below
 * are what keep it from growing without limit.
 */
class StructureCache(private val dir: File, private val maxBytes: Long = 32L * 1024 * 1024) {

    private val lock = Mutex()

    /** The stored PNG for [key], or null when it isn't on the device. */
    suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = fileFor(key)
        if (!file.isFile) return@withContext null
        runCatching {
            val bytes = file.readBytes()
            // Touch it so the least recently *used* drawing is the one evicted, not the oldest.
            file.setLastModified(System.currentTimeMillis())
            bytes.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Stores a drawing. A failure here is not worth interrupting the user for: it only costs a re-fetch. */
    suspend fun write(key: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val file = fileFor(key)
                // Written beside the real name and moved into place, so a half-written file is
                // never read back as a drawing.
                val partial = File(dir, file.name + ".part")
                partial.writeBytes(bytes)
                if (!partial.renameTo(file)) {
                    partial.delete()
                    return@runCatching
                }
            }
            trim()
        }
    }

    /** Drops the least recently used drawings until the folder is back under [maxBytes]. */
    private suspend fun trim() = lock.withLock {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile } ?: return@runCatching
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return@runCatching
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= maxBytes) return@runCatching
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { dir.deleteRecursively() }
        Unit
    }

    /** How much is stored, in bytes. */
    suspend fun size(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    /**
     * A key holds a SMILES, which can be long and contains characters a file name can't, so the
     * name is its digest. A collision would show the wrong molecule, so this is SHA-256 rather
     * than hashCode().
     */
    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".png")
    }
}
