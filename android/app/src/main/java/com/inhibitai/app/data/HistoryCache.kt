package com.inhibitai.app.data

import android.content.Context
import com.inhibitai.app.data.remote.InhibitApi
import com.inhibitai.app.data.remote.SubmissionDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The user's prediction history, kept on the device so the app opens with results instead of a
 * blank screen when the server can't be reached (and so a demo survives the network dropping).
 *
 * What is stored is the server's own JSON for each submission, which means the cache needs no
 * schema of its own and maps to [Compound] through exactly the same code as a live response.
 * It lives in app-private storage, and is cleared on sign-out along with the session.
 */
class HistoryCache(context: Context) {

    @Serializable
    private data class Cached(val userId: Long, val savedAt: Long, val items: List<SubmissionDto>)

    /** A history read from the device, with when it was last brought up to date. */
    data class Snapshot(val compounds: List<Compound>, val savedAt: Long)

    private val file = File(context.applicationContext.filesDir, "history-cache.json")

    suspend fun save(userId: Long, submissions: List<SubmissionDto>) = withContext(Dispatchers.IO) {
        runCatching {
            val json = InhibitApi.json.encodeToString(
                Cached.serializer(), Cached(userId, System.currentTimeMillis(), submissions),
            )
            file.writeText(json)
        }
        Unit
    }

    /** The cached history for [userId], or null when there is none (or it belongs to someone else). */
    suspend fun load(userId: Long): Snapshot? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val cached = runCatching {
            InhibitApi.json.decodeFromString(Cached.serializer(), file.readText())
        }.getOrNull() ?: return@withContext null
        if (cached.userId != userId) return@withContext null
        Snapshot(cached.items.map { it.toCompound() }, cached.savedAt)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        Unit
    }
}
