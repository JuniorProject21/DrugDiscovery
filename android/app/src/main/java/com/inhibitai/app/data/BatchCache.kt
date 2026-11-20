package com.inhibitai.app.data

import com.inhibitai.app.data.remote.InhibitApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The last finished screening run, kept on the device.
 *
 * A run of several hundred compounds takes a while and is the most expensive thing the app asks
 * the server to do. Android is free to kill a backgrounded app at any point — answer a call while
 * a run is finishing and the results would otherwise be gone, with nothing to show for the wait.
 * Written as soon as a run finishes and read back when the app starts.
 */
class BatchCache(private val file: File) {

    @Serializable
    private data class Cached(
        val userId: Long,
        val savedAt: Long,
        val warnings: List<String>,
        val result: BatchResult,
    )

    /** A run read back from the device. */
    data class Saved(val result: BatchResult, val warnings: List<String>, val savedAt: Long)

    suspend fun save(userId: Long, result: BatchResult, warnings: List<String>) = withContext(Dispatchers.IO) {
        runCatching {
            val cached = Cached(userId, System.currentTimeMillis(), warnings, result)
            file.parentFile?.mkdirs()
            file.writeText(InhibitApi.json.encodeToString(Cached.serializer(), cached))
        }
        Unit
    }

    /** The stored run for [userId], or null when there is none (or it belongs to another account). */
    suspend fun load(userId: Long): Saved? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        val cached = runCatching {
            InhibitApi.json.decodeFromString(Cached.serializer(), file.readText())
        }.getOrNull() ?: return@withContext null
        if (cached.userId != userId) return@withContext null
        Saved(cached.result, cached.warnings, cached.savedAt)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        Unit
    }
}
