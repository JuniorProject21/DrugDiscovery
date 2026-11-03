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
    val email: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("has_password") val hasPassword: Boolean = true,
    @SerialName("google_linked") val googleLinked: Boolean = false,
)

@Serializable
data class TokenResponse(@SerialName("access_token") val accessToken: String, val user: UserDto)

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

@Serializable
data class ProfileUpdate(@SerialName("full_name") val fullName: String)

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
    val adme: JsonObject? = null,
    @SerialName("model_version") val modelVersion: String,
    val target: String,
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
