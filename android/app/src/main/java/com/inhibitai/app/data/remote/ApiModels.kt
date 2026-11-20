package com.inhibitai.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// JSON shapes of the InhibitAI backend (see backend/app/api/schemas.py).

@Serializable
data class RegisterRequest(val email: String, val password: String, @SerialName("full_name") val fullName: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class UserDto(
    val id: Long,
    // Null for an account created with a phone number, so one of the two is always present.
    val email: String? = null,
    val phone: String? = null,
    @SerialName("full_name") val fullName: String,
    val institution: String? = null,
    val role: String? = null,
    @SerialName("research_area") val researchArea: String? = null,
    val age: Int? = null,
    @SerialName("has_password") val hasPassword: Boolean = true,
    @SerialName("google_linked") val googleLinked: Boolean = false,
)

@Serializable
data class TokenResponse(@SerialName("access_token") val accessToken: String, val user: UserDto)

// ---------- phone number sign-up (backend /auth/phone/*) ----------

@Serializable
data class PhoneSignUpRequest(
    val phone: String,
    val password: String,
    @SerialName("full_name") val fullName: String,
)

@Serializable
data class PhoneCodeRequest(val phone: String)

@Serializable
data class PhoneVerifyRequest(val phone: String, val code: String)

@Serializable
data class PhoneLoginRequest(val phone: String, val password: String)

@Serializable
data class PhonePasswordResetConfirm(
    val phone: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class GoogleSignInRequest(@SerialName("id_token") val idToken: String)

@Serializable
data class PasswordResetRequest(val email: String)

@Serializable
data class PasswordResetConfirm(
    val email: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
)

/** Only the fields that are set are sent (the JSON config drops nulls), and an empty string
 *  clears a detail on the server. */
@Serializable
data class ProfileUpdate(
    @SerialName("full_name") val fullName: String? = null,
    val institution: String? = null,
    val role: String? = null,
    @SerialName("research_area") val researchArea: String? = null,
    val age: Int? = null,
)

@Serializable
data class PasswordChange(
    @SerialName("current_password") val currentPassword: String? = null,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class MessageResponse(val detail: String)

@Serializable
data class SmilesRequest(val smiles: String)

@Serializable
data class ValidateResponse(
    val valid: Boolean,
    val error: String? = null,
    @SerialName("canonical_smiles") val canonicalSmiles: String? = null,
    val formula: String? = null,
    @SerialName("molecular_weight") val molecularWeight: Double? = null,
    @SerialName("common_name") val commonName: String? = null,
    @SerialName("iupac_name") val iupacName: String? = null,
    @SerialName("pubchem_cid") val pubchemCid: Long? = null,
)

@Serializable
data class DepictRequest(
    val smiles: String,
    val width: Int,
    val height: Int,
    val theme: String,
    val format: String = "png",
    // Atom indices in `smiles` to shade, used by the "why this result" panel.
    @SerialName("highlight_atoms") val highlightAtoms: List<Int> = emptyList(),
)

@Serializable
data class PredictionDto(
    val label: String,
    val score: Double,
    val confidence: Double,
    val threshold: Double,
    @SerialName("p_value_active") val pValueActive: Double? = null,
    @SerialName("p_value_inactive") val pValueInactive: Double? = null,
    @SerialName("nearest_similarity") val nearestSimilarity: Double? = null,
    @SerialName("in_domain") val inDomain: Boolean? = null,
    val reliability: String? = null,
    val pic50: Double? = null,
    val adme: AdmeDto? = null,
    @SerialName("model_version") val modelVersion: String,
    val target: String,
)

// ---------- ADME (computed from the structure on the backend, see app/domain/adme.py) ----------

@Serializable
data class AdmePropertiesDto(
    @SerialName("molecular_weight") val molecularWeight: Double? = null,
    val logp: Double? = null,
    val tpsa: Double? = null,
    val hbd: Int? = null,
    val hba: Int? = null,
    @SerialName("rotatable_bonds") val rotatableBonds: Int? = null,
    @SerialName("aromatic_rings") val aromaticRings: Int? = null,
    @SerialName("heavy_atoms") val heavyAtoms: Int? = null,
    @SerialName("fraction_csp3") val fractionCsp3: Double? = null,
    val qed: Double? = null,
)

@Serializable
data class AdmeRuleDto(val name: String, val passed: Boolean, val detail: String)

@Serializable
data class AdmeAlertDto(val catalog: String, val description: String)

@Serializable
data class AdmeDto(
    /** "rule-based": computed from the structure, never a model prediction. */
    val method: String? = null,
    val absorption: String? = null,
    val distribution: String? = null,
    val metabolism: String? = null,
    val toxicity: String? = null,
    val properties: AdmePropertiesDto? = null,
    val rules: List<AdmeRuleDto> = emptyList(),
    val alerts: List<AdmeAlertDto> = emptyList(),
    val summary: String? = null,
)

@Serializable
data class SubmissionDto(
    val id: Long,
    val name: String,
    @SerialName("input_smiles") val inputSmiles: String,
    @SerialName("canonical_smiles") val canonicalSmiles: String,
    @SerialName("common_name") val commonName: String? = null,
    @SerialName("iupac_name") val iupacName: String? = null,
    @SerialName("pubchem_cid") val pubchemCid: Long? = null,
    val formula: String? = null,
    @SerialName("molecular_weight") val molecularWeight: Double? = null,
    @SerialName("is_saved") val isSaved: Boolean,
    @SerialName("created_at") val createdAt: String,
    val prediction: PredictionDto,
    val cached: Boolean = false,
)

@Serializable
data class SubmissionPage(val items: List<SubmissionDto>, val total: Int)

/** Only the fields that are set are sent (the JSON config drops nulls). */
@Serializable
data class SubmissionPatch(
    @SerialName("is_saved") val isSaved: Boolean? = null,
    val name: String? = null,
)

@Serializable
data class SimilarRequest(
    val smiles: String,
    @SerialName("min_similarity") val minSimilarity: Double,
    val limit: Int,
)

@Serializable
data class SimilarHit(val similarity: Double, val submission: SubmissionDto)

// ---------- batch screening (backend /predictions/batch) ----------

@Serializable
data class BatchRequest(
    val smiles: List<String>,
    val labels: List<String?>? = null,
    val save: Boolean = false,
)

@Serializable
data class BatchRowDto(
    val index: Int,
    @SerialName("input_smiles") val inputSmiles: String,
    @SerialName("label_from_file") val labelFromFile: String? = null,
    val ok: Boolean,
    val error: String? = null,
    val name: String? = null,
    @SerialName("canonical_smiles") val canonicalSmiles: String? = null,
    val formula: String? = null,
    @SerialName("molecular_weight") val molecularWeight: Double? = null,
    val prediction: PredictionDto? = null,
    @SerialName("submission_id") val submissionId: Long? = null,
)

@Serializable
data class BatchResponseDto(
    val rows: List<BatchRowDto>,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val saved: Boolean,
)

// ---------- "why this result" (backend /predictions/explain) ----------

@Serializable
data class FragmentDto(
    val fragment: String,
    val weight: Double,
    val radius: Int,
    val atoms: List<Int>,
    val occurrences: Int,
)

@Serializable
data class ExplanationDto(
    @SerialName("canonical_smiles") val canonicalSmiles: String,
    val score: Double,
    val threshold: Double,
    val label: String,
    @SerialName("model_version") val modelVersion: String,
    val fragments: List<FragmentDto>,
)

// ---------- "learn more" assistant (backend /chat) ----------

@Serializable
data class ChatTurnDto(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val smiles: String,
    val question: String? = null,
    val name: String? = null,
    val history: List<ChatTurnDto> = emptyList(),
)

@Serializable
data class ChatResponseDto(val reply: String, val model: String)

@Serializable
data class ModelInfoDto(
    @SerialName("model_version") val modelVersion: String,
    @SerialName("is_stub") val isStub: Boolean,
    val threshold: Double? = null,
    @SerialName("conformal_significance") val conformalSignificance: Double? = null,
    @SerialName("applicability_min_similarity") val minSimilarity: Double? = null,
    @SerialName("applicability_high_similarity") val highSimilarity: Double? = null,
    val metrics: JsonObject? = null,
)

@Serializable
data class ErrorBody(val detail: JsonElement? = null)
