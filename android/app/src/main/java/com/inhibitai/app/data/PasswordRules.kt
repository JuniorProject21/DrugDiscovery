package com.inhibitai.app.data

/**
 * What makes an acceptable new password, mirrored from the server so the user is told before
 * the round trip rather than after it: at least 12 characters, mixing at least three of lower
 * case, upper case, numbers and symbols (progress report, Table 6-5).
 *
 * The server decides. This only saves a failed request, so if the two ever drift apart the
 * server's answer is the one the user sees.
 */
object PasswordRules {

    const val MIN_LENGTH = 12
    private const val MIN_KINDS = 3

    /** The hint to show under the field before anything is typed. */
    const val SUMMARY = "At least 12 characters, mixing upper and lower case, numbers or symbols."

    /** What is wrong with [password] as a new password, or null when nothing is. */
    fun problem(password: String): String? {
        if (password.length < MIN_LENGTH) return "Password must be at least $MIN_LENGTH characters."
        val kinds = listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }
        if (kinds < MIN_KINDS) {
            return "Password must mix at least three of: lower case, upper case, numbers, symbols."
        }
        return null
    }
}
