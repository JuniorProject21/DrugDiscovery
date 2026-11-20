package com.inhibitai.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inhibitai.app.data.ApiException
import com.inhibitai.app.data.Backend
import com.inhibitai.app.data.BatchResult
import com.inhibitai.app.data.ChatMessage
import com.inhibitai.app.data.Compound
import com.inhibitai.app.data.DayBar
import com.inhibitai.app.data.Explanation
import com.inhibitai.app.data.HistoryCache
import com.inhibitai.app.data.InhibitRepository
import com.inhibitai.app.data.ModelInfo
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.data.User
import com.inhibitai.app.data.UserRole
import com.inhibitai.app.ui.export.CsvExport
import com.inhibitai.app.ui.export.CsvFormatException
import com.inhibitai.app.ui.export.CsvImport
import com.inhibitai.app.ui.export.ParsedCsv
import com.inhibitai.app.ui.notifications.AppNotification
import com.inhibitai.app.ui.notifications.NotificationKind
import com.inhibitai.app.ui.notifications.NotificationSeen
import com.inhibitai.app.ui.notifications.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

/** Screening a whole CSV of compounds (the Batch screen). */
sealed interface BatchState {
    data object Idle : BatchState
    /** Reading the picked file on the device. */
    data object Reading : BatchState
    /** The server is predicting; a few hundred compounds take a while. */
    data class Screening(val fileName: String, val count: Int) : BatchState
    data class Done(val result: BatchResult, val warnings: List<String>) : BatchState
    data class Failed(val message: String) : BatchState
}

/** The "why this result" panel. */
sealed interface ExplainState {
    data object Loading : ExplainState
    data class Ready(val explanation: Explanation) : ExplainState
    /** No explanation to show: the placeholder model, or the server couldn't be reached. */
    data class Unavailable(val message: String) : ExplainState
}

/**
 * [repo] defaults to the real backend, so the app builds the ViewModel the usual way; a test
 * passes a stand-in instead. @JvmOverloads keeps the plain (Application) constructor that
 * Compose's `viewModel()` looks for.
 */
class InhibitViewModel @JvmOverloads constructor(
    app: Application,
    private val repo: Backend = InhibitRepository(app),
) : AndroidViewModel(app) {

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

    /**
     * The phone number a sign-up code was texted to, which moves the Sign Up screen on to its
     * "enter the code" step (progress report figure 11-3).
     */
    var phoneCodeSentTo by mutableStateOf<String?>(null)
        private set

    val user: User? get() = (authState as? AuthState.SignedIn)?.user
    val userName: String get() = user?.fullName.orEmpty()
    /** The email address, or the phone number for an account created with one. */
    val userEmail: String get() = user?.identifier.orEmpty()

    /** "Dr. Noor - University of Sharjah": printed on an exported report so it says who ran it. */
    val reportAuthor: String?
        get() = user?.let { u ->
            listOfNotNull(u.fullName.takeIf { it.isNotBlank() }, u.institution?.takeIf { it.isNotBlank() })
                .joinToString(" - ").ifBlank { null }
        }

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

    var batchState by mutableStateOf<BatchState>(BatchState.Idle)
        private set

    /**
     * The phone number a password-reset code was texted to; the Forgot password screen then asks
     * for the code. Phone accounts have no email, so this is their way back in.
     */
    var phoneResetSentTo by mutableStateOf<String?>(null)
        private set

    // ---- the "learn more" assistant ----
    val chatMessages = mutableStateListOf<ChatMessage>()
    var chatBusy by mutableStateOf(false)
        private set
    var chatError by mutableStateOf<String?>(null)
        private set
    /** Which compound the open conversation is about, so switching results starts a fresh one. */
    var chatCompoundId by mutableStateOf<String?>(null)
        private set

    // ---- notifications ----
    /** Things that happened while the app was running, newest first (batch runs, failures). */
    private val events = mutableStateListOf<AppNotification>()

    /**
     * When the history on screen was read from the device rather than the server (no connection),
     * this is when that copy was last brought up to date. Null while the list is live.
     */
    var historyCachedAt by mutableStateOf<Long?>(null)
        private set

    // Explanations already fetched, so reopening a result doesn't ask the server again.
    private val explanations = mutableMapOf<String, Explanation>()

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
            if (saved != null) {
                // Put the device's copy on screen first, so the app never opens blank while the
                // history is being fetched (and still shows results if the fetch fails).
                showCachedHistory()
                // A screening run that finished before the app was killed is still worth showing.
                repo.lastBatch()?.let { batchState = BatchState.Done(it.result, it.warnings) }
                refresh()
            }
        }
    }

    /** Puts the history stored on this device on screen. True when there was one. */
    private suspend fun showCachedHistory(): Boolean {
        val snapshot: HistoryCache.Snapshot = repo.cachedHistory() ?: return false
        if (snapshot.compounds.isEmpty()) return false
        compounds.clear()
        compounds.addAll(snapshot.compounds)
        historyCachedAt = snapshot.savedAt
        return true
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

    // ---------- phone number sign-up and sign-in ----------

    /** Step 1 of signing up with a phone number: the server texts a 6-digit code. */
    fun startPhoneSignUp(fullName: String, phone: String, password: String) {
        if (authBusy) return
        authError = null
        viewModelScope.launch {
            authBusy = true
            try {
                authInfo = repo.startPhoneSignUp(fullName, phone, password)
                phoneCodeSentTo = phone.trim()
            } catch (e: ApiException) {
                authError = e.message
            } finally {
                authBusy = false
            }
        }
    }

    /** Step 2: the right code both verifies the number and signs the new account in. */
    fun verifyPhone(code: String) {
        val phone = phoneCodeSentTo ?: return
        authenticate { repo.verifyPhone(phone, code).also { clearPhoneSignUp() } }
    }

    fun resendPhoneCode() {
        val phone = phoneCodeSentTo ?: return
        if (authBusy) return
        authError = null
        viewModelScope.launch {
            authBusy = true
            try {
                authInfo = repo.resendPhoneCode(phone)
            } catch (e: ApiException) {
                authError = e.message
            } finally {
                authBusy = false
            }
        }
    }

    fun phoneLogin(phone: String, password: String) = authenticate { repo.phoneLogin(phone, password) }

    fun clearPhoneSignUp() {
        phoneCodeSentTo = null
        authInfo = null
    }

    /** Step 1 of resetting a forgotten password on a phone account: the server texts a code. */
    fun requestPhonePasswordReset(phone: String) {
        if (authBusy) return
        authError = null
        viewModelScope.launch {
            authBusy = true
            try {
                authInfo = repo.requestPhonePasswordReset(phone)
                phoneResetSentTo = phone.trim()
            } catch (e: ApiException) {
                authError = e.message
            } finally {
                authBusy = false
            }
        }
    }

    /** Step 2: the code plus a new password, which also signs the user in. */
    fun confirmPhonePasswordReset(code: String, newPassword: String) {
        val phone = phoneResetSentTo ?: return
        authenticate { repo.confirmPhonePasswordReset(phone, code, newPassword).also { clearPhoneReset() } }
    }

    fun clearPhoneReset() {
        phoneResetSentTo = null
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

    /**
     * Saves the Account screen. Only the arguments given are sent, so a field left out keeps its
     * value; an empty string takes that detail off the profile.
     */
    fun updateProfile(
        onDone: () -> Unit,
        fullName: String? = null,
        institution: String? = null,
        role: UserRole? = null,
        clearRole: Boolean = false,
        researchArea: String? = null,
        age: Int? = null,
    ) = accountAction(onDone) {
        authState = AuthState.SignedIn(repo.updateProfile(
            fullName = fullName,
            institution = institution,
            role = if (clearRole) "" else role?.key,
            researchArea = researchArea,
            age = age,
        ))
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
        historyCachedAt = null
        batchState = BatchState.Idle
        undoJob?.cancel()
        undoJob = null
        pendingDelete = null
        explanations.clear()
        chatMessages.clear()
        chatCompoundId = null
        events.clear()
        authState = AuthState.SignedOut
        if (reason != null) message = reason
    }

    // ---------- history ----------

    fun refresh() {
        if (isLoading) return
        viewModelScope.launch {
            isLoading = true
            try {
                // The server still holds a delete that is waiting out its undo window.
                flushPendingDelete()
                guarded {
                    val history = repo.history()
                    compounds.clear()
                    compounds.addAll(history)
                    loadError = null
                    historyCachedAt = null  // what's on screen is live again
                    repo.currentUser().let { authState = AuthState.SignedIn(it) }
                    modelInfo = try {
                        repo.modelInfo()
                    } catch (e: ApiException) {
                        if (e.isUnauthorized) throw e
                        null // no model on the server yet; predictions will explain why
                    }
                }.onFailure { failure ->
                    loadError = failure.message
                    // The server is unreachable: keep working from the copy on this device.
                    if ((failure as? ApiException)?.isOffline == true && showCachedHistory()) {
                        loadError = null
                    }
                }
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

    /**
     * A delete the user can still take back. The rows are off the list straight away but still on
     * the server until the undo window closes; [indices] are where they sat, so undo puts each one
     * back in its old place rather than at the top.
     */
    data class PendingDelete(val compounds: List<Compound>, val indices: List<Int>) {
        val count: Int get() = compounds.size
        val label: String
            get() = if (count == 1) "Deleted ${compounds.first().name}" else "Deleted $count predictions"
    }

    /** Set while a delete can still be undone; the History screen shows its undo bar. */
    var pendingDelete by mutableStateOf<PendingDelete?>(null)
        private set
    private var undoJob: Job? = null

    fun delete(id: String) = delete(listOf(id))

    /** Takes the rows off the list and starts the undo window. The server call waits until it closes. */
    fun delete(ids: List<String>) {
        // Only one delete can be waiting, so an earlier one goes through now.
        commitPendingDelete()
        val indices = ids
            .mapNotNull { id -> compounds.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            .distinct()
            .sorted()
        if (indices.isEmpty()) return
        val removed = indices.map { compounds[it] }
        removed.forEach { compounds.remove(it) }
        pendingDelete = PendingDelete(removed, indices)
        undoJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            undoJob = null
            flushPendingDelete()
        }
    }

    /** Puts the rows back. Nothing reached the server yet, so there is nothing to undo there. */
    fun undoDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        undoJob?.cancel()
        undoJob = null
        pending.indices.zip(pending.compounds).forEach { (index, compound) ->
            compounds.add(index.coerceIn(0, compounds.size), compound)
        }
    }

    /** Sends a waiting delete to the server now, because the user moved on or the list is being re-read. */
    fun commitPendingDelete() {
        if (pendingDelete == null) return
        viewModelScope.launch { flushPendingDelete() }
    }

    private suspend fun flushPendingDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        undoJob?.cancel()
        undoJob = null
        val failed = mutableListOf<Pair<Int, Compound>>()
        pending.indices.zip(pending.compounds).forEach { (index, compound) ->
            guarded { repo.delete(compound.id) }.onFailure { failed += index to compound }
        }
        if (failed.isNotEmpty()) {
            // The server still has them, so they belong back on the list.
            failed.forEach { (index, compound) -> compounds.add(index.coerceIn(0, compounds.size), compound) }
            message = if (failed.size == 1) {
                "Couldn't delete ${failed.first().second.name}. It's back in your history."
            } else {
                "Couldn't delete ${failed.size} predictions. They're back in your history."
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
                        if (compound.status == PredictionStatus.ACTIVE && !compound.isStubResult) {
                            Notifications.show(
                                getApplication(), "Active hit: ${compound.name}",
                                "Predicted active with ${compound.confidence}% confidence" +
                                    (compound.reliability?.let { ", $it reliability" } ?: "") + ".",
                            )
                        }
                        onResult(compound.id)
                    }
                    .onFailure { predictionError = it.message ?: "Prediction failed." }
            } finally {
                isPredicting = false
            }
        }
    }

    suspend fun structureImage(
        smiles: String,
        widthPx: Int,
        heightPx: Int,
        dark: Boolean,
        highlightAtoms: List<Int> = emptyList(),
    ): ImageBitmap = repo.structureImage(smiles, widthPx, heightPx, dark, highlightAtoms)

    // ---------- batch screening ----------

    /**
     * Screens the CSV the user picked. The file is read on the device, then sent as one request;
     * rows that could not be read come back with their reason instead of failing the whole file.
     */
    fun screenFile(uri: Uri, saveToHistory: Boolean) {
        if (batchState is BatchState.Reading || batchState is BatchState.Screening) return
        viewModelScope.launch {
            batchState = BatchState.Reading
            val parsed: ParsedCsv = try {
                CsvImport.read(getApplication(), uri)
            } catch (e: CsvFormatException) {
                batchState = BatchState.Failed(e.message ?: "Couldn't read that file.")
                return@launch
            } catch (e: Exception) {
                batchState = BatchState.Failed("Couldn't read that file: ${e.message}")
                return@launch
            }
            batchState = BatchState.Screening(parsed.fileName, parsed.rows.size)
            guarded { repo.predictBatch(parsed.rows, saveToHistory, parsed.fileName) }
                .onSuccess { result ->
                    batchState = BatchState.Done(result, parsed.warnings)
                    // Stored before anything else: this is the expensive thing to lose.
                    repo.saveLastBatch(result, parsed.warnings)
                    val summary = "${result.succeeded} of ${result.total} compounds predicted, " +
                        "${result.activeCount} active" +
                        if (result.failed > 0) ", ${result.failed} couldn't be read." else "."
                    addEvent(NotificationKind.BATCH, "${parsed.fileName} screened", summary)
                    // The run can take a while, so say so even if the user has left the app.
                    Notifications.show(getApplication(), "Screening finished", summary)
                    // Saved rows are new history entries, so bring the list up to date.
                    if (result.saved && result.rows.any { it.savedId != null }) refresh()
                }
                .onFailure {
                    batchState = BatchState.Failed(it.message ?: "Screening failed.")
                    addEvent(NotificationKind.SYSTEM, "Screening failed", it.message ?: "Please try again.")
                }
        }
    }

    fun clearBatch() {
        batchState = BatchState.Idle
        viewModelScope.launch { repo.clearLastBatch() }
    }

    /** Hands the screening table to the share sheet as a CSV. */
    fun exportBatch(context: Context, onError: (String) -> Unit) {
        val result = (batchState as? BatchState.Done)?.result ?: return
        viewModelScope.launch {
            try {
                CsvExport.shareBatch(context, result)
            } catch (e: Exception) {
                onError("Couldn't create the CSV: ${e.message}")
            }
        }
    }

    // ---------- exporting the history ----------

    var exportingHistory by mutableStateOf(false)
        private set

    /**
     * Writes predictions out as one CSV for analysis in Excel. [rows] is what the History screen
     * is showing, so an exported file matches the filter and search the user set.
     */
    fun exportHistory(context: Context, rows: List<Compound>) {
        if (exportingHistory) return
        if (rows.isEmpty()) {
            message = "There are no predictions to export yet."
            return
        }
        viewModelScope.launch {
            exportingHistory = true
            try {
                CsvExport.shareHistory(context, rows)
            } catch (e: Exception) {
                message = "Couldn't create the CSV: ${e.message}"
            } finally {
                exportingHistory = false
            }
        }
    }

    // ---------- "why this result" ----------

    /**
     * Which fragments moved this compound's score. The answer is kept for the session, so
     * reopening a result doesn't ask the server again.
     */
    suspend fun explain(compound: Compound): ExplainState {
        explanations[compound.canonicalSmiles]?.let { return ExplainState.Ready(it) }
        return guarded { repo.explain(compound.canonicalSmiles) }
            .fold(
                onSuccess = {
                    explanations[compound.canonicalSmiles] = it
                    ExplainState.Ready(it)
                },
                onFailure = { ExplainState.Unavailable(it.message ?: "No explanation is available.") },
            )
    }

    // ---------- the "learn more" assistant ----------

    /**
     * Opens (or re-opens) the conversation about [compound]. Switching to another result starts a
     * fresh conversation, and the first answer is asked for straight away.
     */
    fun openChat(compound: Compound) {
        if (chatCompoundId == compound.id && chatMessages.isNotEmpty()) return
        chatCompoundId = compound.id
        chatMessages.clear()
        chatError = null
        ask(compound, question = null)
    }

    /** Sends the user's own follow-up question. */
    fun askChat(question: String) {
        val compound = chatCompoundId?.let { compound(it) } ?: return
        val text = question.trim()
        if (text.isEmpty() || chatBusy) return
        chatMessages.add(ChatMessage(fromUser = true, text = text))
        ask(compound, question = text)
    }

    private fun ask(compound: Compound, question: String?) {
        if (chatBusy) return
        chatError = null
        viewModelScope.launch {
            chatBusy = true
            // The question just added is the one being asked, so it isn't sent as history too.
            val history = chatMessages.dropLast(if (question != null) 1 else 0).toList()
            guarded { repo.chat(compound.canonicalSmiles, compound.name, question, history) }
                .onSuccess { chatMessages.add(ChatMessage(fromUser = false, text = it)) }
                .onFailure { chatError = it.message ?: "The assistant couldn't answer." }
            chatBusy = false
        }
    }

    /** Asks the last question again after a failure. */
    fun retryChat() {
        val compound = chatCompoundId?.let { compound(it) } ?: return
        val lastQuestion = chatMessages.lastOrNull { it.fromUser }?.text
        chatError = null
        ask(compound, question = lastQuestion)
    }

    fun closeChat() {
        chatError = null
    }

    // ---------- notifications ----------

    /**
     * What the bell shows: things that happened in this session (a batch finishing), plus the
     * active hits in the history, newest first. Deriving the hits from the history rather than
     * storing them means the list is still right after a restart.
     */
    val notifications: List<AppNotification>
        get() {
            val hits = compounds
                .filter { it.status == PredictionStatus.ACTIVE && !it.isStubResult }
                .sortedByDescending { it.timestamp }
                .take(20)
                .map { c ->
                    AppNotification(
                        id = "hit-${c.id}",
                        kind = NotificationKind.ACTIVE_HIT,
                        title = "Active hit: ${c.name}",
                        body = buildString {
                            append("Predicted active with ${c.confidence}% confidence")
                            c.reliability?.let { append(", $it reliability") }
                            append(".")
                        },
                        at = c.timestamp,
                        compoundId = c.id,
                    )
                }
            return (events + hits).sortedByDescending { it.at }
        }

    /** How many notifications have arrived since the user last opened the bell. */
    val unreadNotifications: Int
        get() = notifications.count { it.at.isAfter(lastSeenNotifications) }

    private var lastSeenNotifications by mutableStateOf(NotificationSeen.load(getApplication()))

    fun markNotificationsSeen() {
        lastSeenNotifications = LocalDateTime.now()
        NotificationSeen.save(getApplication(), lastSeenNotifications)
    }

    private fun addEvent(kind: NotificationKind, title: String, body: String, compoundId: String? = null) {
        events.add(0, AppNotification(
            id = "event-${System.currentTimeMillis()}-${events.size}",
            kind = kind, title = title, body = body, at = LocalDateTime.now(), compoundId = compoundId,
        ))
    }

    // ---------- offline ----------

    /** Explains, when it applies, that the results on screen came from this device. */
    val offlineNotice: String?
        get() = historyCachedAt?.let {
            val when_ = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM d, hh:mm a", Locale.US))
            "Offline: showing the ${compounds.size} predictions saved on this device on $when_. " +
                "Tap Retry once the server is reachable."
        }

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

/** How long a deleted prediction can be brought back before the server is told. */
internal const val UNDO_WINDOW_MS = 6_000L
