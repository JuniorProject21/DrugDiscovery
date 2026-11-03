package com.inhibitai.app.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PredictionStatus(val label: String) {
    ACTIVE("Active"),
    INACTIVE("Inactive"),
    // The model could not commit to either label at its stated error rate (conformal prediction).
    UNCERTAIN("Uncertain"),
}

enum class Level(val label: String) { LOW("Low"), MODERATE("Moderate"), GOOD("Good"), HIGH("High") }

data class AdmeProfile(
    val absorption: Level,
    val distribution: Level,
    val metabolism: Level,
    val toxicity: Level,
)

data class User(
    val id: Long,
    val email: String,
    val fullName: String,
    val hasPassword: Boolean = true,   // false for accounts that only sign in with Google
    val googleLinked: Boolean = false,
)

/** What the server says about the loaded model, for the Profile screen. */
data class ModelInfo(
    val version: String,
    val isStub: Boolean,
    val threshold: Double?,
    val conformalConfidence: Double?,  // e.g. 0.80 when the conformal significance is 0.20
    val minSimilarity: Double?,
    val highSimilarity: Double?,
    val metrics: Map<String, String>,
)

data class Compound(
    val id: String,                   // backend id, used for navigation and API calls
    val name: String,                 // compound name from PubChem (e.g. Olaparib), else XYZ-001; renamable
    val smiles: String,               // as entered
    val canonicalSmiles: String,      // standardised structure the model actually saw
    val commonName: String? = null,   // from PubChem, when the compound is known there
    val iupacName: String? = null,
    val pubchemCid: Long? = null,
    val formula: String? = null,
    val molecularWeight: Double? = null,
    val status: PredictionStatus,
    val confidence: Int,              // 0..100
    val score: Float,                 // fraction of trees voting active; not a probability of being right
    val threshold: Float,             // the score at or above which the model calls a compound active
    val timestamp: LocalDateTime,
    val adme: AdmeProfile?,           // null until the backend has an ADME model
    val nearestSimilarity: Float?,    // Tanimoto similarity to the closest training compound
    val inDomain: Boolean?,           // false = unlike the chemistry the model was trained on
    val reliability: String?,         // High / Medium / Low, from nearestSimilarity (0.7 / 0.5 bands)
    val pic50: Float?,
    val isSaved: Boolean = false,
    val target: String = "PARP-1",
    val modelVersion: String = "",
) {
    val isActive: Boolean get() = status == PredictionStatus.ACTIVE

    val isStubResult: Boolean get() = modelVersion.startsWith("stub")

    val formattedDate: String get() = timestamp.format(DATE_FORMAT)

    val pubchemUrl: String? get() = pubchemCid?.let { "https://pubchem.ncbi.nlm.nih.gov/compound/$it" }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy · hh:mm a", Locale.US)
    }
}

data class DayBar(val day: String, val active: Int, val inactive: Int, val uncertain: Int = 0) {
    val total: Int get() = active + inactive + uncertain
}
