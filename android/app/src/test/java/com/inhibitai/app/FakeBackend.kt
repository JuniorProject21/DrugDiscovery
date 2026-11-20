package com.inhibitai.app

import androidx.compose.ui.graphics.ImageBitmap
import com.inhibitai.app.data.Backend
import com.inhibitai.app.data.BatchCache
import com.inhibitai.app.data.BatchRow
import com.inhibitai.app.data.BatchResult
import com.inhibitai.app.data.ChatMessage
import com.inhibitai.app.data.Compound
import com.inhibitai.app.data.Explanation
import com.inhibitai.app.data.HistoryCache
import com.inhibitai.app.data.ModelInfo
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.data.User
import com.inhibitai.app.data.remote.ValidateResponse
import java.time.LocalDateTime

/**
 * A backend that answers from memory. Every call a test hasn't set up fails loudly rather than
 * returning something plausible, so a test can only pass for the reason it claims to.
 */
open class FakeBackend : Backend {

    var user: User? = null

    /** The history the "server" holds. */
    val stored = mutableListOf<Compound>()

    /** What the app has asked the server to delete, in order. */
    val deleted = mutableListOf<String>()

    /** Set to make [history] fail the way a dead server does. */
    var historyFailure: Exception? = null

    /** What [cachedHistory] returns: the copy kept on the device. */
    var cached: HistoryCache.Snapshot? = null

    var deleteFailure: Exception? = null

    /** The finished screening run the device is holding, as [saveLastBatch] left it. */
    var savedBatch: BatchCache.Saved? = null

    override suspend fun restoreSession(): User? = user

    override suspend fun cachedHistory(): HistoryCache.Snapshot? = cached

    override suspend fun currentUser(): User = user ?: error("no signed-in user")

    override suspend fun signOut() {
        user = null
    }

    override suspend fun history(): List<Compound> {
        historyFailure?.let { throw it }
        return stored.toList()
    }

    override suspend fun delete(id: String) {
        deleteFailure?.let { throw it }
        deleted += id
        stored.removeAll { it.id == id }
    }

    override suspend fun saveLastBatch(result: BatchResult, warnings: List<String>) {
        savedBatch = BatchCache.Saved(result, warnings, savedAt = 1_763_000_000_000)
    }

    override suspend fun lastBatch(): BatchCache.Saved? = savedBatch

    override suspend fun clearLastBatch() {
        savedBatch = null
    }

    override suspend fun modelInfo(): ModelInfo = ModelInfo(
        version = "test v1", isStub = false, threshold = 0.795, conformalConfidence = 0.8,
        minSimilarity = 0.5, highSimilarity = 0.7, metrics = emptyMap(),
    )

    // ---------- not used by these tests ----------
    private fun unused(): Nothing = throw UnsupportedOperationException("not set up for this test")

    override suspend fun login(email: String, password: String): User = unused()
    override suspend fun register(fullName: String, email: String, password: String): User = unused()
    override suspend fun googleSignIn(idToken: String): User = unused()
    override suspend fun requestPasswordReset(email: String): String = unused()
    override suspend fun confirmPasswordReset(email: String, code: String, newPassword: String): User = unused()
    override suspend fun startPhoneSignUp(fullName: String, phone: String, password: String): String = unused()
    override suspend fun resendPhoneCode(phone: String): String = unused()
    override suspend fun verifyPhone(phone: String, code: String): User = unused()
    override suspend fun phoneLogin(phone: String, password: String): User = unused()
    override suspend fun requestPhonePasswordReset(phone: String): String = unused()
    override suspend fun confirmPhonePasswordReset(phone: String, code: String, newPassword: String): User = unused()
    override suspend fun updateProfile(
        fullName: String?, institution: String?, role: String?, researchArea: String?, age: Int?,
    ): User = unused()
    override suspend fun changePassword(currentPassword: String?, newPassword: String) = unused()
    override suspend fun predict(smiles: String): Compound = unused()
    override suspend fun predictBatch(
        rows: List<Pair<String, String?>>, save: Boolean, fileName: String,
    ): BatchResult = unused()
    override suspend fun explain(smiles: String): Explanation = unused()
    override suspend fun similar(smiles: String, minSimilarity: Double, limit: Int): List<Pair<Compound, Float>> = unused()
    override suspend fun setSaved(id: String, saved: Boolean): Compound = unused()
    override suspend fun rename(id: String, name: String): Compound = unused()
    override suspend fun validate(smiles: String): ValidateResponse = unused()
    override suspend fun chat(smiles: String, name: String?, question: String?, history: List<ChatMessage>): String = unused()
    override suspend fun structureImage(
        smiles: String, widthPx: Int, heightPx: Int, dark: Boolean, highlightAtoms: List<Int>,
    ): ImageBitmap = unused()
}

/** A prediction with just enough filled in to stand in for a real one. */
fun compound(
    id: String,
    name: String = "Compound $id",
    status: PredictionStatus = PredictionStatus.INACTIVE,
    isSaved: Boolean = false,
    at: LocalDateTime = LocalDateTime.of(2026, 11, 20, 12, 0),
) = Compound(
    id = id,
    name = name,
    smiles = "c1ccccc1",
    canonicalSmiles = "c1ccccc1",
    status = status,
    confidence = 90,
    score = 0.5f,
    threshold = 0.795f,
    timestamp = at,
    adme = null,
    nearestSimilarity = null,
    inDomain = null,
    reliability = null,
    pic50 = null,
    isSaved = isSaved,
    modelVersion = "test v1",
)

/** A finished screening run: two compounds predicted, one line the parser couldn't read. */
fun batchResult(fileName: String = "library.csv") = BatchResult(
    rows = listOf(
        BatchRow(index = 0, inputSmiles = "c1ccccc1", labelFromFile = "16F", name = "16F",
            canonicalSmiles = "c1ccccc1", status = PredictionStatus.ACTIVE, confidence = 88, score = 0.81f,
            threshold = 0.795f),
        BatchRow(index = 1, inputSmiles = "CC(=O)O", labelFromFile = "17F", name = "17F",
            canonicalSmiles = "CC(=O)O", status = PredictionStatus.INACTIVE, confidence = 95, score = 0.12f,
            threshold = 0.795f),
        BatchRow(index = 2, inputSmiles = "not-a-molecule", labelFromFile = null,
            error = "Could not read this structure."),
    ),
    saved = true,
    fileName = fileName,
)

fun user(id: Long = 1, name: String = "Dr. Noor") = User(id = id, email = "noor@example.com", fullName = name)
