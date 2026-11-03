package com.inhibitai.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.inhibitai.app.BuildConfig
import com.inhibitai.app.data.remote.DepictRequest
import com.inhibitai.app.data.remote.ErrorBody
import com.inhibitai.app.data.remote.GoogleSignInRequest
import com.inhibitai.app.data.remote.InhibitApi
import com.inhibitai.app.data.remote.LoginRequest
import com.inhibitai.app.data.remote.ModelInfoDto
import com.inhibitai.app.data.remote.PasswordChange
import com.inhibitai.app.data.remote.PasswordResetConfirm
import com.inhibitai.app.data.remote.PasswordResetRequest
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
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** A failed backend call, with a message that can be shown to the user as-is. */
class ApiException(message: String, val code: Int? = null) : Exception(message) {
    val isUnauthorized: Boolean get() = code == 401
}

/**
 * The app's single gateway to the backend. Screens and the ViewModel never see HTTP or JSON.
 */
class InhibitRepository(
    context: Context,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) {
    private val session = SessionStore(context.applicationContext)

    @Volatile
    private var token: String? = null

    private val api = InhibitApi.create(baseUrl) { token }

    // Structure drawings keyed by SMILES + size + theme, so lists don't re-download them.
    private val structures = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // ---------- session ----------

    /** The user saved on this device, if any. Works offline; the server checks the token later. */
    suspend fun restoreSession(): User? {
        val saved = session.load() ?: return null
        token = saved.token
        return saved.user
    }

    suspend fun login(email: String, password: String): User = signIn { api.login(LoginRequest(email.trim(), password)) }

    suspend fun register(fullName: String, email: String, password: String): User =
        signIn { api.register(RegisterRequest(email.trim(), password, fullName.trim())) }

    suspend fun googleSignIn(idToken: String): User = signIn { api.google(GoogleSignInRequest(idToken)) }

    /** Emails a reset code; returns the server's message to show. */
    suspend fun requestPasswordReset(email: String): String =
        call { api.requestPasswordReset(PasswordResetRequest(email.trim())) }.detail

    suspend fun confirmPasswordReset(email: String, code: String, newPassword: String): User =
        signIn { api.confirmPasswordReset(PasswordResetConfirm(email.trim(), code.trim(), newPassword)) }

    suspend fun currentUser(): User = call { api.me() }.toUser().also { session.updateUser(it) }

    suspend fun updateProfile(fullName: String): User =
        call { api.updateMe(ProfileUpdate(fullName.trim())) }.toUser().also { session.updateUser(it) }

    suspend fun changePassword(currentPassword: String?, newPassword: String) {
        val response = call { api.changePassword(PasswordChange(currentPassword, newPassword)) }
        if (!response.isSuccessful) throw httpError(response.code(), response.errorBody()?.string())
    }

    suspend fun signOut() {
        token = null
        structures.evictAll()
        session.clear()
    }

    private suspend fun signIn(request: suspend () -> TokenResponse): User {
        val response = call { request() }
        val user = response.user.toUser()
        token = response.accessToken
        session.save(response.accessToken, user)
        return user
    }

    // ---------- predictions ----------

    suspend fun history(): List<Compound> = call { api.history() }.items.map { it.toCompound() }

    suspend fun predict(smiles: String): Compound = call { api.predict(SmilesRequest(smiles)) }.toCompound()

    suspend fun setSaved(id: String, saved: Boolean): Compound =
        call { api.updatePrediction(id.toLong(), SubmissionPatch(isSaved = saved)) }.toCompound()

    suspend fun rename(id: String, name: String): Compound =
        call { api.updatePrediction(id.toLong(), SubmissionPatch(name = name.trim())) }.toCompound()

    /** The user's earlier predictions with a similar structure, most similar first. */
    suspend fun similar(smiles: String, minSimilarity: Double = 0.6, limit: Int = 6): List<Pair<Compound, Float>> =
        call { api.similar(SimilarRequest(smiles, minSimilarity, limit)) }
            .map { it.submission.toCompound() to it.similarity.toFloat() }

    suspend fun delete(id: String) {
        val response = call { api.delete(id.toLong()) }
        if (!response.isSuccessful) throw ApiException("Could not delete this prediction.", response.code())
    }

    suspend fun modelInfo(): ModelInfo = call { api.model() }.toModelInfo()

    // ---------- chemistry ----------

    suspend fun validate(smiles: String): ValidateResponse = call { api.validate(SmilesRequest(smiles)) }

    suspend fun structureImage(smiles: String, widthPx: Int, heightPx: Int, dark: Boolean): ImageBitmap {
        val key = "$smiles|$widthPx|$heightPx|$dark"
        structures.get(key)?.let { return it }
        val body = call { api.depict(DepictRequest(smiles, widthPx, heightPx, if (dark) "dark" else "light")) }
        val image = withContext(Dispatchers.IO) {
            val bytes = body.use { it.bytes() }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } ?: throw ApiException("Could not draw this structure.")
        structures.put(key, image)
        return image
    }

    // ---------- plumbing ----------

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        throw httpError(e.code(), runCatching { e.response()?.errorBody()?.string() }.getOrNull())
    } catch (e: IOException) {
        throw ApiException("Can't reach the InhibitAI server at $baseUrl. Check that the backend is running.")
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

private fun UserDto.toUser() = User(id, email, fullName, hasPassword, googleLinked)

private fun ModelInfoDto.toModelInfo() = ModelInfo(
    version = modelVersion,
    isStub = isStub,
    threshold = threshold,
    conformalConfidence = conformalSignificance?.let { 1 - it },
    minSimilarity = minSimilarity,
    highSimilarity = highSimilarity,
    metrics = metrics.orEmpty().mapValues { (_, v) -> v.jsonPrimitive.contentOrNull.orEmpty() },
)

private fun SubmissionDto.toCompound(): Compound {
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
        status = when (p.label) {
            "active" -> PredictionStatus.ACTIVE
            "inactive" -> PredictionStatus.INACTIVE
            else -> PredictionStatus.UNCERTAIN
        },
        confidence = (p.confidence * 100).roundToInt().coerceIn(0, 100),
        score = p.score.toFloat(),
        threshold = p.threshold.toFloat(),
        timestamp = OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
        adme = p.adme?.let { adme ->
            fun level(key: String) = adme[key]?.jsonPrimitive?.contentOrNull
                ?.let { value -> Level.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
            val absorption = level("absorption")
            val distribution = level("distribution")
            val metabolism = level("metabolism")
            val toxicity = level("toxicity")
            if (absorption != null && distribution != null && metabolism != null && toxicity != null) {
                AdmeProfile(absorption, distribution, metabolism, toxicity)
            } else {
                null
            }
        },
        nearestSimilarity = p.nearestSimilarity?.toFloat(),
        inDomain = p.inDomain,
        reliability = p.reliability?.replaceFirstChar { it.uppercase() },
        pic50 = p.pic50?.toFloat(),
        isSaved = isSaved,
        target = p.target,
        modelVersion = p.modelVersion,
    )
}
