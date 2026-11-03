package com.inhibitai.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inhibitai.app.data.ApiException
import com.inhibitai.app.data.Compound
import com.inhibitai.app.data.DayBar
import com.inhibitai.app.data.InhibitRepository
import com.inhibitai.app.data.ModelInfo
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.data.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

sealed interface AuthState {
    data object Checking : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: User) : AuthState
}

/** Result of checking the SMILES on the Predict screen while the user types. */
sealed interface SmilesCheck {
    data object Checking : SmilesCheck
    data class Valid(
        val input: String,
        val canonicalSmiles: String,
        val formula: String?,
        val molecularWeight: Double?,
        val commonName: String?,  // from PubChem, when the compound is known there
    ) : SmilesCheck
    data class Invalid(val message: String) : SmilesCheck
    data class Unavailable(val message: String) : SmilesCheck
}

class InhibitViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InhibitRepository(app)

    var authState by mutableStateOf<AuthState>(AuthState.Checking)
        private set
    var authBusy by mutableStateOf(false)
        private set
    var authError by mutableStateOf<String?>(null)
        private set

    /** Shown on the sign-in screens, e.g. "we've sent a code". */
    var authInfo by mutableStateOf<String?>(null)
        private set
    /** The email a password reset code was sent to; the Forgot password screen then asks for the code. */
    var resetCodeSentTo by mutableStateOf<String?>(null)
        private set

    val user: User? get() = (authState as? AuthState.SignedIn)?.user
    val userName: String get() = user?.fullName.orEmpty()
    val userEmail: String get() = user?.email.orEmpty()

    var accountBusy by mutableStateOf(false)
        private set
    var accountError by mutableStateOf<String?>(null)
        private set

    val compounds = mutableStateListOf<Compound>()
    var isLoading by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var modelInfo by mutableStateOf<ModelInfo?>(null)
        private set
    val modelVersion: String? get() = modelInfo?.version

    var isPredicting by mutableStateOf(false)
        private set
    var predictionError by mutableStateOf<String?>(null)
        private set
    var smilesCheck by mutableStateOf<SmilesCheck?>(null)
        private set
    private var checkJob: Job? = null

    /** One-off message for a toast (e.g. a failed save); cleared with [consumeMessage]. */
    var message by mutableStateOf<String?>(null)
        private set

    val total: Int get() = compounds.size
    val activeCount: Int get() = compounds.count { it.status == PredictionStatus.ACTIVE }
    val inactiveCount: Int get() = compounds.count { it.status == PredictionStatus.INACTIVE }
    val uncertainCount: Int get() = compounds.count { it.status == PredictionStatus.UNCERTAIN }
    val savedCount: Int get() = compounds.count { it.isSaved }
    val averageConfidence: Int
        get() = if (compounds.isEmpty()) 0 else compounds.map { it.confidence }.average().roundToInt()

    init {
        viewModelScope.launch {
            val saved = repo.restoreSession()
            authState = if (saved != null) AuthState.SignedIn(saved) else AuthState.SignedOut
            if (saved != null) refresh()
        }
    }

    fun compound(id: String): Compound? = compounds.firstOrNull { it.id == id }

    /** Compounds predicted in the last [days] days. */
    fun within(days: Long): List<Compound> {
        val from = LocalDateTime.now().minusDays(days)
        return compounds.filter { it.timestamp.isAfter(from) }
    }

    /** Predictions per day for the last 7 days, oldest first. */
    fun lastSevenDays(): List<DayBar> {
        val today = LocalDate.now()
        return (6 downTo 0).map { ago ->
            val day = today.minusDays(ago.toLong())
            val onDay = compounds.filter { it.timestamp.toLocalDate() == day }
            DayBar(
                day = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US),
                active = onDay.count { it.status == PredictionStatus.ACTIVE },
                inactive = onDay.count { it.status == PredictionStatus.INACTIVE },
                uncertain = onDay.count { it.status == PredictionStatus.UNCERTAIN },
            )
        }
    }

    // ---------- auth ----------

    fun login(email: String, password: String) = authenticate { repo.login(email, password) }

    fun register(fullName: String, email: String, password: String) =
        authenticate { repo.register(fullName, email, password) }

    fun googleSignIn(idToken: String) = authenticate { repo.googleSignIn(idToken) }

    /** Shows an error from the Google account picker on the sign-in screen. */
    fun showAuthError(message: String) {
        authError = message
    }

    fun requestPasswordReset(email: String) {
        if (authBusy) return
        authError = null
        viewModelScope.launch {
            authBusy = true
            try {
                authInfo = repo.requestPasswordReset(email)
                resetCodeSentTo = email.trim()
            } catch (e: ApiException) {
                authError = e.message
            } finally {
                authBusy = false
            }
        }
    }

    fun confirmPasswordReset(code: String, newPassword: String) {
        val email = resetCodeSentTo ?: return
        authenticate { repo.confirmPasswordReset(email, code, newPassword).also { clearPasswordReset() } }
    }

    fun clearPasswordReset() {
        resetCodeSentTo = null
        authInfo = null
    }

    private fun authenticate(block: suspend () -> User) {
        if (authBusy) return
        authError = null
        viewModelScope.launch {
            authBusy = true
            try {
                authState = AuthState.SignedIn(block())
                refresh()
            } catch (e: ApiException) {
                authError = e.message
            } finally {
                authBusy = false
            }
        }
    }

    fun clearAuthError() {
        authError = null
    }

    // ---------- account ----------

    fun updateProfile(fullName: String, onDone: () -> Unit) = accountAction(onDone) {
        authState = AuthState.SignedIn(repo.updateProfile(fullName))
    }

    fun changePassword(currentPassword: String?, newPassword: String, onDone: () -> Unit) = accountAction(onDone) {
        repo.changePassword(currentPassword, newPassword)
        authState = AuthState.SignedIn(repo.currentUser())  // has_password may have just become true
        message = "Password updated."
    }

    fun clearAccountError() {
        accountError = null
    }

    private fun accountAction(onDone: () -> Unit, block: suspend () -> Unit) {
        if (accountBusy) return
        accountError = null
        viewModelScope.launch {
            accountBusy = true
            try {
                guarded { block() }.onSuccess { onDone() }.onFailure { accountError = it.message }
            } finally {
                accountBusy = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch { signOut() }
    }

    private suspend fun signOut(reason: String? = null) {
        repo.signOut()
        compounds.clear()
        modelInfo = null
        loadError = null
        smilesCheck = null
        authState = AuthState.SignedOut
        if (reason != null) message = reason
    }

    // ---------- history ----------

    fun refresh() {
        if (isLoading) return
        viewModelScope.launch {
            isLoading = true
            try {
                guarded {
                    val history = repo.history()
                    compounds.clear()
                    compounds.addAll(history)
                    loadError = null
                    repo.currentUser().let { authState = AuthState.SignedIn(it) }
                    modelInfo = try {
                        repo.modelInfo()
                    } catch (e: ApiException) {
                        if (e.isUnauthorized) throw e
                        null // no model on the server yet; predictions will explain why
                    }
                }.onFailure { loadError = it.message }
            } finally {
                isLoading = false
            }
        }
    }

    fun toggleSaved(id: String) {
        val i = compounds.indexOfFirst { it.id == id }
        if (i < 0) return
        val before = compounds[i]
        compounds[i] = before.copy(isSaved = !before.isSaved)
        viewModelScope.launch {
            guarded { repo.setSaved(id, !before.isSaved) }.onFailure { e ->
                val j = compounds.indexOfFirst { it.id == id }
                if (j >= 0) compounds[j] = compounds[j].copy(isSaved = before.isSaved)
                message = "Couldn't update ${before.name}: ${e.message}"
            }
        }
    }

    fun delete(id: String) {
        val i = compounds.indexOfFirst { it.id == id }
        if (i < 0) return
        val removed = compounds.removeAt(i)
        viewModelScope.launch {
            guarded { repo.delete(id) }.onFailure { e ->
                compounds.add(i.coerceAtMost(compounds.size), removed)
                message = "Couldn't delete ${removed.name}: ${e.message}"
            }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            guarded { repo.rename(id, name) }
                .onSuccess { renamed ->
                    val i = compounds.indexOfFirst { it.id == id }
                    if (i >= 0) compounds[i] = renamed
                }
                .onFailure { message = "Couldn't rename: ${it.message}" }
        }
    }

    /** The user's other predictions with a similar structure (up to [limit]), most similar first. */
    suspend fun similarTo(compound: Compound, limit: Int = 3): List<Pair<Compound, Float>> =
        guarded { repo.similar(compound.canonicalSmiles) }.getOrDefault(emptyList())
            .filter { (other, _) -> other.id != compound.id }
            .take(limit)

    fun consumeMessage() {
        message = null
    }

    // ---------- prediction ----------

    fun clearError() {
        predictionError = null
    }

    /** Validate the SMILES on the server shortly after the user stops typing. */
    fun checkSmiles(smiles: String) {
        checkJob?.cancel()
        val input = smiles.trim()
        if (input.isEmpty()) {
            smilesCheck = null
            return
        }
        smilesCheck = SmilesCheck.Checking
        checkJob = viewModelScope.launch {
            delay(400)
            guarded { repo.validate(input) }
                .onSuccess { r ->
                    smilesCheck = if (r.valid) {
                        SmilesCheck.Valid(input, r.canonicalSmiles.orEmpty(), r.formula, r.molecularWeight, r.commonName)
                    } else {
                        SmilesCheck.Invalid(r.error ?: "Invalid SMILES.")
                    }
                }
                .onFailure { smilesCheck = SmilesCheck.Unavailable(it.message ?: "Couldn't check this SMILES.") }
        }
    }

    fun predict(smiles: String, onResult: (String) -> Unit) {
        val input = smiles.trim()
        if (input.isEmpty()) {
            predictionError = "Please enter a SMILES string."
            return
        }
        if (isPredicting) return
        predictionError = null
        viewModelScope.launch {
            isPredicting = true
            try {
                guarded { repo.predict(input) }
                    .onSuccess { compound ->
                        compounds.add(0, compound)
                        onResult(compound.id)
                    }
                    .onFailure { predictionError = it.message ?: "Prediction failed." }
            } finally {
                isPredicting = false
            }
        }
    }

    suspend fun structureImage(smiles: String, widthPx: Int, heightPx: Int, dark: Boolean): ImageBitmap =
        repo.structureImage(smiles, widthPx, heightPx, dark)

    /** Runs a backend call; an expired session signs the user out. */
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.isUnauthorized && authState is AuthState.SignedIn) signOut(e.message)
        Result.failure(e)
    }
}
