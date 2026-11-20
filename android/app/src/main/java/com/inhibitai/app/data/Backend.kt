package com.inhibitai.app.data

import androidx.compose.ui.graphics.ImageBitmap
import com.inhibitai.app.data.remote.ValidateResponse

/**
 * Everything the app asks of the backend, as one interface.
 *
 * [InhibitRepository] is the real implementation, talking to the FastAPI server. The ViewModel
 * depends on this rather than on that class, so a test can hand it a stand-in and check what the
 * app does with a slow server, a refused sign-in or no network at all — without a server running.
 */
interface Backend {

    // ---------- session ----------
    suspend fun restoreSession(): User?
    suspend fun cachedHistory(): HistoryCache.Snapshot?
    suspend fun currentUser(): User
    suspend fun signOut()

    // ---------- sign in with an email address ----------
    suspend fun login(email: String, password: String): User
    suspend fun register(fullName: String, email: String, password: String): User
    suspend fun googleSignIn(idToken: String): User
    suspend fun requestPasswordReset(email: String): String
    suspend fun confirmPasswordReset(email: String, code: String, newPassword: String): User

    // ---------- sign in with a phone number ----------
    suspend fun startPhoneSignUp(fullName: String, phone: String, password: String): String
    suspend fun resendPhoneCode(phone: String): String
    suspend fun verifyPhone(phone: String, code: String): User
    suspend fun phoneLogin(phone: String, password: String): User
    suspend fun requestPhonePasswordReset(phone: String): String
    suspend fun confirmPhonePasswordReset(phone: String, code: String, newPassword: String): User

    // ---------- account ----------
    suspend fun updateProfile(
        fullName: String? = null,
        institution: String? = null,
        role: String? = null,
        researchArea: String? = null,
        age: Int? = null,
    ): User
    suspend fun changePassword(currentPassword: String?, newPassword: String)

    // ---------- predictions ----------
    suspend fun history(): List<Compound>
    suspend fun predict(smiles: String): Compound
    suspend fun predictBatch(rows: List<Pair<String, String?>>, save: Boolean, fileName: String): BatchResult

    /** Keeps a finished screening run on the device, so it survives the app being killed. */
    suspend fun saveLastBatch(result: BatchResult, warnings: List<String>)
    suspend fun lastBatch(): BatchCache.Saved?
    suspend fun clearLastBatch()
    suspend fun explain(smiles: String): Explanation
    suspend fun similar(smiles: String, minSimilarity: Double = 0.6, limit: Int = 6): List<Pair<Compound, Float>>
    suspend fun setSaved(id: String, saved: Boolean): Compound
    suspend fun rename(id: String, name: String): Compound
    suspend fun delete(id: String)
    suspend fun modelInfo(): ModelInfo

    // ---------- chemistry and the assistant ----------
    suspend fun validate(smiles: String): ValidateResponse
    suspend fun chat(smiles: String, name: String?, question: String?, history: List<ChatMessage>): String
    suspend fun structureImage(
        smiles: String,
        widthPx: Int,
        heightPx: Int,
        dark: Boolean,
        highlightAtoms: List<Int> = emptyList(),
    ): ImageBitmap
}
