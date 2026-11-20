package com.inhibitai.app.ui.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file people actually hand the app is rarely the file the app expects: Excel's semicolons,
 * a byte-order mark, quoted names with commas in them, a missing header. These pin down what is
 * accepted and what is refused with an explanation.
 */
class CsvImportTest {

    private fun parse(text: String) = CsvImport.parse(text, "compounds.csv")

    @Test
    fun `reads a named SMILES column and its name column`() {
        val parsed = parse(
            """
            name,smiles
            Olaparib,O=C1NN=Cc2ccccc21
            Aspirin,CC(=O)Oc1ccccc1C(=O)O
            """.trimIndent(),
        )
        assertEquals(2, parsed.rows.size)
        assertEquals("O=C1NN=Cc2ccccc21" to "Olaparib", parsed.rows[0])
        assertEquals("CC(=O)Oc1ccccc1C(=O)O" to "Aspirin", parsed.rows[1])
    }

    @Test
    fun `a file of bare structures needs no header`() {
        val parsed = parse("c1ccccc1\nCC(=O)O")
        assertEquals(2, parsed.rows.size)
        assertEquals("c1ccccc1", parsed.rows[0].first)
        assertNull("there is no name column to take one from", parsed.rows[0].second)
    }

    @Test
    fun `Excel's semicolons and byte-order mark are handled`() {
        val parsed = parse("﻿name;smiles\n16F;c1ccccc1C(=O)N")
        assertEquals(1, parsed.rows.size)
        assertEquals("c1ccccc1C(=O)N" to "16F", parsed.rows[0])
    }

    @Test
    fun `tab-separated files are handled`() {
        val parsed = parse("smiles\tname\nc1ccccc1\tbenzene")
        assertEquals("c1ccccc1" to "benzene", parsed.rows[0])
    }

    @Test
    fun `a quoted name keeps the comma inside it`() {
        val parsed = parse("name,smiles\n\"Compound 4, batch 2\",c1ccccc1")
        assertEquals("Compound 4, batch 2", parsed.rows[0].second)
    }

    @Test
    fun `blank lines are ignored rather than counted as rows`() {
        val parsed = parse("smiles\n\nc1ccccc1\n\n\nCC(=O)O\n")
        assertEquals(2, parsed.rows.size)
    }

    @Test
    fun `a row with no structure is skipped and said so`() {
        val parsed = parse("name,smiles\nOlaparib,O=C1NN=Cc2ccccc21\nMissing,\nAspirin,CC(=O)O")
        assertEquals(2, parsed.rows.size)
        assertEquals(1, parsed.warnings.size)
        assertTrue("the warning should name the line", parsed.warnings[0].contains("Line 3"))
    }

    @Test
    fun `only the first 500 compounds are taken, and the rest are reported`() {
        val parsed = parse((1..520).joinToString("\n") { "c1ccccc1" })
        assertEquals(CsvImport.MAX_ROWS, parsed.rows.size)
        assertTrue(parsed.warnings.any { it.contains("20 more were left out") })
    }

    @Test
    fun `a file with no structures is refused with an explanation`() {
        val error = runCatching { parse("name,notes\nOlaparib,a PARP inhibitor") }.exceptionOrNull()
        assertTrue(error is CsvFormatException)
        assertTrue(error!!.message.orEmpty().contains("No SMILES found"))
    }

    @Test
    fun `an empty file is refused`() {
        val error = runCatching { parse("   \n\n") }.exceptionOrNull()
        assertTrue(error is CsvFormatException)
        assertEquals("That file is empty.", error!!.message)
    }
}
