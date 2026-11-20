package com.inhibitai.app.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

enum class PredictionStatus(val label: String) {
    ACTIVE("Active"),
    INACTIVE("Inactive"),
    // The model could not commit to either label at its stated error rate (conformal prediction).
    UNCERTAIN("Uncertain"),
}

enum class Level(val label: String) { LOW("Low"), MODERATE("Moderate"), GOOD("Good"), HIGH("High") }

/** One drug-likeness rule or alert check, with the numbers that decided it. */
data class AdmeRule(val name: String, val passed: Boolean, val detail: String)

/** A structural alert: a fragment known to interfere with assays or to be unwanted in a lead. */
data class AdmeAlert(val catalog: String, val description: String)

/** The physicochemical numbers every verdict below is built from. */
data class AdmeProperties(
    val molecularWeight: Double?,
    val logP: Double?,
    val tpsa: Double?,
    val hbd: Int?,
    val hba: Int?,
    val rotatableBonds: Int?,
    val aromaticRings: Int?,
    val heavyAtoms: Int?,
    val fractionCsp3: Double?,
    val qed: Double?,
)

/**
 * ADME computed from the structure with published rules (Lipinski, Veber, PAINS/Brenk alerts),
 * not predicted by a trained model. [ruleBased] is what the card says so out loud.
 */
data class AdmeProfile(
    val absorption: Level,
    val distribution: Level,
    val metabolism: Level,
    val toxicity: Level,
    val ruleBased: Boolean = true,
    val summary: String? = null,
    val properties: AdmeProperties? = null,
    val rules: List<AdmeRule> = emptyList(),
    val alerts: List<AdmeAlert> = emptyList(),
)

/**
 * A signed-in account. It is identified by an email address or by a phone number: accounts created
 * with a phone number have no email, so exactly one of the two can be missing.
 */
/** Who the user is, as picked from a list on the Account screen. */
enum class UserRole(val key: String, val label: String) {
    STUDENT("student", "Student"),
    RESEARCHER("researcher", "Researcher"),
    SUPERVISOR("supervisor", "Supervisor"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

data class User(
    val id: Long,
    val email: String? = null,
    val phone: String? = null,         // E.164, e.g. +971501234567, once verified
    val fullName: String,
    val institution: String? = null,
    val role: UserRole? = null,
    val researchArea: String? = null,
    val age: Int? = null,
    val hasPassword: Boolean = true,   // false for accounts that only sign in with Google
    val googleLinked: Boolean = false,
) {
    /** What to show as "who you are": the email, or the phone number for a phone account. */
    val identifier: String get() = email ?: phone.orEmpty()

    val signsInWithPhone: Boolean get() = email == null && phone != null
}

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

// ---------- batch screening ----------

/**
 * One row of an uploaded CSV: a prediction, or the reason that line could not be read. A bad line
 * never hides the rest of the file, so the table can show exactly which rows need fixing.
 */
@Serializable
data class BatchRow(
    val index: Int,                    // line in the file, starting at 0
    val inputSmiles: String,
    val labelFromFile: String?,
    val error: String? = null,
    val name: String? = null,
    val canonicalSmiles: String? = null,
    val formula: String? = null,
    val molecularWeight: Double? = null,
    val status: PredictionStatus? = null,
    val confidence: Int? = null,
    val score: Float? = null,
    val threshold: Float? = null,
    val reliability: String? = null,
    val savedId: Long? = null,         // set when the row was added to the history
) {
    val ok: Boolean get() = error == null
    val displayName: String get() = name ?: labelFromFile ?: "Row ${index + 1}"
}

@Serializable
data class BatchResult(
    val rows: List<BatchRow>,
    val saved: Boolean,
    val fileName: String,
) {
    val total: Int get() = rows.size
    val succeeded: Int get() = rows.count { it.ok }
    val failed: Int get() = rows.count { !it.ok }
    val activeCount: Int get() = rows.count { it.status == PredictionStatus.ACTIVE }
}

// ---------- "why this result" ----------

/** One piece of chemistry and what the score loses without it (see backend explain.py). */
data class Fragment(
    val fragment: String,              // the piece itself, as SMILES
    val weight: Float,                 // positive = pushed the score towards Active
    val radius: Int,                   // how far from its central atom the piece reaches
    val atoms: List<Int>,              // atom indices in the explanation's canonicalSmiles
    val occurrences: Int,              // how many places in the molecule it appears
)

/**
 * Why the model gave this score: the fragments that moved it most, largest effect first. The atom
 * indices refer to [canonicalSmiles], which is the structure the panel draws.
 */
data class Explanation(
    val canonicalSmiles: String,
    val score: Float,
    val threshold: Float,
    val status: PredictionStatus,
    val modelVersion: String,
    val fragments: List<Fragment>,
) {
    val pushedUp: List<Fragment> get() = fragments.filter { it.weight > 0 }
    val pushedDown: List<Fragment> get() = fragments.filter { it.weight < 0 }
}

// ---------- the "learn more" assistant ----------

/** One line of the conversation about a result. */
data class ChatMessage(val fromUser: Boolean, val text: String)
