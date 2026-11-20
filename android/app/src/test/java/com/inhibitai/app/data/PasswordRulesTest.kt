package com.inhibitai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app's copy of the server's password rule.
 *
 * The examples below are deliberately the same ones as `test_a_weak_password_is_refused_when_registering`
 * and `test_a_password_that_mixes_three_kinds_is_accepted` in `backend/tests/test_accounts.py`.
 * If the two rules ever drift apart, one of these two suites goes red.
 */
class PasswordRulesTest {

    @Test
    fun `too short is refused however well it mixes`() {
        assertEquals("Password must be at least 12 characters.", PasswordRules.problem("Short1!"))
        assertNotNull(PasswordRules.problem("Aa1!Aa1!Aa1"))  // eleven
        assertNull(PasswordRules.problem("Aa1!Aa1!Aa1!"))    // twelve
    }

    @Test
    fun `long but all one kind is refused`() {
        listOf("abcdefghijkl", "ABCDEFGHIJKL", "123456789012", "passwordword").forEach {
            assertNotNull("$it should be refused", PasswordRules.problem(it))
        }
    }

    @Test
    fun `two kinds is still not enough`() {
        assertNotNull(PasswordRules.problem("correct-horse"))  // lower case and a symbol
        assertNotNull(PasswordRules.problem("Correcthorses"))  // upper and lower case
    }

    @Test
    fun `three kinds is accepted`() {
        listOf("Correct-horse9", "correct horse 9", "PARP1-inhibitor", "aaaaaaaaaaaA1").forEach {
            assertNull("$it should be accepted", PasswordRules.problem(it))
        }
    }

    @Test
    fun `the message says what is wrong`() {
        assertEquals(
            "Password must mix at least three of: lower case, upper case, numbers, symbols.",
            PasswordRules.problem("abcdefghijklmnop"),
        )
    }
}
