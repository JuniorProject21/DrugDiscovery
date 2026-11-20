package com.inhibitai.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.inhibitai.app.BuildConfig
import com.inhibitai.app.data.remote.AdmeDto
import com.inhibitai.app.data.remote.BatchRequest
import com.inhibitai.app.data.remote.ChatRequest
import com.inhibitai.app.data.remote.ChatTurnDto
import com.inhibitai.app.data.remote.BatchResponseDto
import com.inhibitai.app.data.remote.BatchRowDto
import com.inhibitai.app.data.remote.DepictRequest
import com.inhibitai.app.data.remote.ErrorBody
import com.inhibitai.app.data.remote.ExplanationDto
import com.inhibitai.app.data.remote.GoogleSignInRequest
import com.inhibitai.app.data.remote.InhibitApi
import com.inhibitai.app.data.remote.LoginRequest
import com.inhibitai.app.data.remote.ModelInfoDto
import com.inhibitai.app.data.remote.PasswordChange
import com.inhibitai.app.data.remote.PasswordResetConfirm
import com.inhibitai.app.data.remote.PasswordResetRequest
import com.inhibitai.app.data.remote.PhoneCodeRequest
import com.inhibitai.app.data.remote.PhoneLoginRequest
import com.inhibitai.app.data.remote.PhonePasswordResetConfirm
import com.inhibitai.app.data.remote.PhoneSignUpRequest
import com.inhibitai.app.data.remote.PhoneVerifyRequest
import com.inhibitai.app.data.remote.ProfileUpdate
import com.inhibitai.app.data.remote.RegisterRequest
import com.inhibitai.app.data.remote.SimilarRequest
import com.inhibitai.app.data.remote.SmilesRequest
import com.inhibitai.app.data.remote.SubmissionDto
import com.inhibitai.app.data.remote.SubmissionPatch
import com.inhibitai.app.data.remote.TokenResponse
import com.inhibitai.app.data.remote.UserDto
import com.inhibitai.app.data.remote.ValidateResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** A failed backend call, with a message that can be shown to the user as-is. */
class ApiException(
    message: String,
    val code: Int? = null,
    /** True when the server was never reached (no network, server down, tunnel expired). */
    val isOffline: Boolean = false,
) : Exception(message) {
    val isUnauthorized: Boolean get() = code == 401
}

/**
 * The app's single gateway to the backend. Screens and the ViewModel never see HTTP or JSON.
 */
class InhibitRepository(
    context: Context,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) : Backend {
    private val session = SessionStore(context.applicationContext)
    private val cache = HistoryCache(context)

    // Drawings kept on the device, so a cold start isn't one request per thumbnail and the
    // offline history still shows its structures.
    private val structureFiles = StructureCache(File(context.applicationContext.filesDir, "structures"))

    // The last screening run, so a finished batch isn't lost when Android reclaims the app.
    private val batchFile = BatchCache(File(context.applicationContext.filesDir, "batch-cache.json"))

    @Volatile
    private var token: String? = null

    // Which account the cached history belongs to.
    @Volatile
    private var userId: Long? = null

    private val api = InhibitApi.create(baseUrl) { token }

    // Structure drawings keyed by SMILES + size + theme, so lists don't re-download them.
    private val structures = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // ---------- session ----------

    /** The user saved on this device, if any. Works offline; the server checks the token later. */
    override suspend fun restoreSession(): User? {
        val saved = session.load() ?: return null
        token = saved.token
        userId = saved.user.id
        return saved.user
    }

    /**
     * The history stored on this device for the signed-in user, if any. Shown while the live
     * history is still loading, and kept on screen when the server can't be reached.
     */
    override suspend fun cachedHistory(): HistoryCache.Snapshot? = userId?.let { cache.load(it) }

    override suspend fun login(email: String, password: String): User = signIn { api.login(LoginRequest(email.trim(), password)) }

    override suspend fun register(fullName: String, email: String, password: String): User =
        signIn { api.register(RegisterRequest(email.trim(), password, fullName.trim())) }

    override suspend fun googleSignIn(idToken: String): User = signIn { api.google(GoogleSignInRequest(idToken)) }

    /** Emails a reset code; returns the server's message to show. */
    override suspend fun requestPasswordReset(email: String): String =
        call { api.requestPasswordReset(PasswordResetRequest(email.trim())) }.detail

    override suspend fun confirmPasswordReset(email: String, code: String, newPassword: String): User =
        signIn { api.confirmPasswordReset(PasswordResetConfirm(email.trim(), code.trim(), newPassword)) }

    // ---------- phone number ----------

    /** Starts a phone sign-up and returns the server's message saying where the code went. */
    override suspend fun startPhoneSignUp(fullName: String, phone: String, password: String): String =
        call { api.phoneSignUp(PhoneSignUpRequest(phone.trim(), password, fullName.trim())) }.detail

    override suspend fun resendPhoneCode(phone: String): String =
        call { api.phoneResendCode(PhoneCodeRequest(phone.trim())) }.detail

    /** Entering the right code both verifies the number and signs the new account in. */
    override suspend fun verifyPhone(phone: String, code: String): User =
        signIn { api.phoneVerify(PhoneVerifyRequest(phone.trim(), code.trim())) }

    override suspend fun phoneLogin(phone: String, password: String): User =
        signIn { api.phoneLogin(PhoneLoginRequest(phone.trim(), password)) }

    /** Texts a reset code; returns the server's message to show. */
    override suspend fun requestPhonePasswordReset(phone: String): String =
        call { api.requestPhonePasswordReset(PhoneCodeRequest(phone.trim())) }.detail

    override suspend fun confirmPhonePasswordReset(phone: String, code: String, newPassword: String): User =
        signIn { api.confirmPhonePasswordReset(PhonePasswordResetConfirm(phone.trim(), code.trim(), newPassword)) }

    override suspend fun currentUser(): User = call { api.me() }.toUser().also { session.updateUser(it) }

    /**
     * Saves the profile. Only the arguments given are sent, so anything left out keeps its value;
     * an empty string clears that detail on the server.
     */
    override suspend fun updateProfile(
        fullName: String?,
        institution: String?,
        role: String?,
        researchArea: String?,
        age: Int?,
    ): User = call {
        api.updateMe(ProfileUpdate(fullName?.trim(), institution?.trim(), role, researchArea?.trim(), age))
    }.toUser().also { session.updateUser(it) }

    override suspend fun changePassword(currentPassword: String?, newPassword: String) {
        val response = call { api.changePassword(PasswordChange(currentPassword, newPassword)) }
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
    }

    override suspend fun signOut() {
        structureFiles.clear()
        batchFile.clear()
        token = null
        userId = null
        structures.evictAll()
        session.clear()
        cache.clear()  // the stored history belongs to that account
    }

    private suspend fun signIn(request: suspend () -> TokenResponse): User {
        val response = call { request() }
        val user = response.user.toUser()
        val wasSomeoneElse = userId != null && userId != user.id
        token = response.accessToken
        userId = user.id
        if (wasSomeoneElse) cache.clear()
        session.save(response.accessToken, user)
        return user
    }

    // ---------- predictions ----------

    /** The live history, which also refreshes the copy stored on the device. */
    override suspend fun history(): List<Compound> {
        val items = call { api.history() }.items
        userId?.let { cache.save(it, items) }
        return items.map { it.toCompound() }
    }

    /**
     * Screens a whole file of compounds in one request. Rows that cannot be read come back with
     * their reason instead of failing the call, so the table can show which lines need fixing.
     */
    override suspend fun predictBatch(rows: List<Pair<String, String?>>, save: Boolean, fileName: String): BatchResult =
        call { api.predictBatch(BatchRequest(rows.map { it.first }, rows.map { it.second }, save)) }
            .toBatchResult(fileName)

    /**
     * Asks the assistant about one compound. Only the structure and the question are sent: the
     * server looks the numbers up itself, so the answer is grounded in the real prediction.
     */
    override suspend fun chat(smiles: String, name: String?, question: String?, history: List<ChatMessage>): String =
        call {
            api.chat(ChatRequest(
                smiles = smiles, question = question, name = name,
                history = history.map { ChatTurnDto(if (it.fromUser) "user" else "assistant", it.text) },
            ))
        }.reply

    /** Which fragments moved this compound's score, for the "why this result" panel. */
    override suspend fun explain(smiles: String): Explanation = call { api.explain(SmilesRequest(smiles)) }.toExplanation()

    override suspend fun predict(smiles: String): Compound = call { api.predict(SmilesRequest(smiles)) }.toCompound()

    override suspend fun setSaved(id: String, saved: Boolean): Compound =
        call { api.updatePrediction(id.toLong(), SubmissionPatch(isSaved = saved)) }.toCompound()

    override suspend fun rename(id: String, name: String): Compound =
        call { api.updatePrediction(id.toLong(), SubmissionPatch(name = name.trim())) }.toCompound()

    /** The user's earlier predictions with a similar structure, most similar first. */
    override suspend fun similar(smiles: String, minSimilarity: Double, limit: Int): List<Pair<Compound, Float>> =
        call { api.similar(SimilarRequest(smiles, minSimilarity, limit)) }
            .map { it.submission.toCompound() to it.similarity.toFloat() }

    override suspend fun delete(id: String) {
        val response = call { api.delete(id.toLong()) }
        if (!response.isSuccessful) throw ApiException("Could not delete this prediction.", response.code())
    }

    override suspend fun saveLastBatch(result: BatchResult, warnings: List<String>) {
        userId?.let { batchFile.save(it, result, warnings) }
    }

    override suspend fun lastBatch(): BatchCache.Saved? = userId?.let { batchFile.load(it) }

    override suspend fun clearLastBatch() = batchFile.clear()

    override suspend fun modelInfo(): ModelInfo = call { api.model() }.toModelInfo()

    // ---------- chemistry ----------

    override suspend fun validate(smiles: String): ValidateResponse = call { api.validate(SmilesRequest(smiles)) }

    override suspend fun structureImage(
        smiles: String,
        widthPx: Int,
        heightPx: Int,
        dark: Boolean,
        highlightAtoms: List<Int>,
    ): ImageBitmap {
        val key = "$smiles|$widthPx|$heightPx|$dark|${highlightAtoms.sorted().joinToString(",")}"
        structures.get(key)?.let { return it }

        // Already on the device from an earlier run: no request, and it works with no network.
        structureFiles.read(key)?.let { stored ->
            decode(stored)?.let {
                structures.put(key, it)
                return it
            }
        }

        val body = call {
            api.depict(DepictRequest(smiles, widthPx, heightPx, if (dark) "dark" else "light",
                highlightAtoms = highlightAtoms))
        }
        val bytes = withContext(Dispatchers.IO) { body.use { it.bytes() } }
        val image = decode(bytes) ?: throw ApiException("Could not draw this structure.")
        structureFiles.write(key, bytes)
        structures.put(key, image)
        return image
    }

    private suspend fun decode(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    // ---------- plumbing ----------

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        throw httpError(e.code(), runCatching { e.response()?.errorBody()?.string() }.getOrNull())
    } catch (e: IOException) {
        throw ApiException(
            "Can't reach the InhibitAI server at $baseUrl. Check that the backend is running.",
            isOffline = true,
        )
    } catch (e: SerializationException) {
        throw ApiException("The server sent an unexpected response.")
    }

    /** The server's `detail` message when it sent one, else a generic message for the status code. */
    private fun httpError(code: Int, rawBody: String?): ApiException {
        val detail = runCatching {
            (InhibitApi.json.decodeFromString(ErrorBody.serializer(), rawBody.orEmpty()).detail as? JsonPrimitive)
                ?.contentOrNull
        }.getOrNull()
        val message = detail ?: when (code) {
            401 -> "Please sign in again."
            503 -> "The prediction model is not available on the server."
            else -> "Server error ($code)."
        }
        return ApiException(message, code)
    }
}

private fun UserDto.toUser() = User(
    id = id, email = email, phone = phone, fullName = fullName, institution = institution,
    role = UserRole.fromKey(role), researchArea = researchArea, age = age,
    hasPassword = hasPassword, googleLinked = googleLinked,
)

private fun level(value: String?): Level? =
    Level.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

private fun AdmeDto.toProfile(): AdmeProfile? {
    // All four levels have to be there, or there is nothing to draw.
    val absorption = level(absorption) ?: return null
    val distribution = level(distribution) ?: return null
    val metabolism = level(metabolism) ?: return null
    val toxicity = level(toxicity) ?: return null
    return AdmeProfile(
        absorption = absorption, distribution = distribution, metabolism = metabolism, toxicity = toxicity,
        ruleBased = method == null || method == "rule-based",
        summary = summary,
        properties = properties?.let {
            AdmeProperties(it.molecularWeight, it.logp, it.tpsa, it.hbd, it.hba, it.rotatableBonds,
                it.aromaticRings, it.heavyAtoms, it.fractionCsp3, it.qed)
        },
        rules = rules.map { AdmeRule(it.name, it.passed, it.detail) },
        alerts = alerts.map { AdmeAlert(it.catalog, it.description) },
    )
}

private fun status(label: String) = when (label) {
    "active" -> PredictionStatus.ACTIVE
    "inactive" -> PredictionStatus.INACTIVE
    else -> PredictionStatus.UNCERTAIN
}

private fun BatchResponseDto.toBatchResult(fileName: String) =
    BatchResult(rows = rows.map { it.toBatchRow() }, saved = saved, fileName = fileName)

private fun BatchRowDto.toBatchRow(): BatchRow {
    val p = prediction
    return BatchRow(
        index = index,
        inputSmiles = inputSmiles,
        labelFromFile = labelFromFile,
        error = error,
        name = name,
        canonicalSmiles = canonicalSmiles,
        formula = formula,
        molecularWeight = molecularWeight,
        status = p?.let { status(it.label) },
        confidence = p?.let { (it.confidence * 100).roundToInt().coerceIn(0, 100) },
        score = p?.score?.toFloat(),
        threshold = p?.threshold?.toFloat(),
        reliability = p?.reliability?.replaceFirstChar { it.uppercase() },
        savedId = submissionId,
    )
}

private fun ExplanationDto.toExplanation() = Explanation(
    canonicalSmiles = canonicalSmiles,
    score = score.toFloat(),
    threshold = threshold.toFloat(),
    status = status(label),
    modelVersion = modelVersion,
    fragments = fragments.map {
        Fragment(it.fragment, it.weight.toFloat(), it.radius, it.atoms, it.occurrences)
    },
)

private fun ModelInfoDto.toModelInfo() = ModelInfo(
    version = modelVersion,
    isStub = isStub,
    threshold = threshold,
    conformalConfidence = conformalSignificance?.let { 1 - it },
    minSimilarity = minSimilarity,
    highSimilarity = highSimilarity,
    metrics = metrics.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() },
)

/** Internal rather than private so [HistoryCache] maps stored rows through this same code. */
internal fun SubmissionDto.toCompound(): Compound {
    val p = prediction
    return Compound(
        id = id.toString(),
        name = name,
        smiles = inputSmiles,
        canonicalSmiles = canonicalSmiles,
        commonName = commonName,
        iupacName = iupacName,
        pubchemCid = pubchemCid,
        formula = formula,
        molecularWeight = molecularWeight,
        status = status(p.label),
        confidence = (p.confidence * 100).roundToInt().coerceIn(0, 100),
        score = p.score.toFloat(),
        threshold = p.threshold.toFloat(),
        timestamp = OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
        adme = p.adme?.toProfile(),
        nearestSimilarity = p.nearestSimilarity?.toFloat(),
        inDomain = p.inDomain,
        reliability = p.reliability?.replaceFirstChar { it.uppercase() },
        pic50 = p.pic50?.toFloat(),
        isSaved = isSaved,
        target = p.target,
        modelVersion = p.modelVersion,
    )
}
