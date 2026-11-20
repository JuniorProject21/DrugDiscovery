package com.inhibitai.app.data

import com.inhibitai.app.batchResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The finished screening run kept on the device. */
class BatchCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache() = BatchCache(File(folder.root, "batch-cache.json"))

    @Test
    fun `a saved run comes back whole`() = runTest {
        val cache = cache()
        cache.save(userId = 1, result = batchResult("library.csv"), warnings = listOf("Line 4 has no SMILES"))

        val saved = cache.load(1)!!
        assertEquals("library.csv", saved.result.fileName)
        assertEquals(3, saved.result.total)
        assertEquals(2, saved.result.succeeded)
        assertEquals(1, saved.result.failed)
        assertEquals(1, saved.result.activeCount)
        assertEquals(listOf("Line 4 has no SMILES"), saved.warnings)
    }

    @Test
    fun `every column of every row survives the round trip`() = runTest {
        val cache = cache()
        cache.save(1, batchResult(), emptyList())

        val row = cache.load(1)!!.result.rows[0]
        assertEquals("16F", row.name)
        assertEquals(PredictionStatus.ACTIVE, row.status)
        assertEquals(88, row.confidence)
        assertEquals(0.81f, row.score!!, 0.0001f)
        assertEquals("c1ccccc1", row.canonicalSmiles)

        val failed = cache.load(1)!!.result.rows[2]
        assertEquals("Could not read this structure.", failed.error)
        assertEquals(false, failed.ok)
    }

    @Test
    fun `another account's run is not shown`() = runTest {
        val cache = cache()
        cache.save(userId = 1, result = batchResult(), warnings = emptyList())

        assertNull(cache.load(2))
    }

    @Test
    fun `nothing saved reads as nothing`() = runTest {
        assertNull(cache().load(1))
    }

    @Test
    fun `a damaged file is ignored rather than crashing the app`() = runTest {
        val file = File(folder.root, "batch-cache.json")
        file.writeText("{ this is not json")

        assertNull(BatchCache(file).load(1))
    }

    @Test
    fun `clearing removes it`() = runTest {
        val cache = cache()
        cache.save(1, batchResult(), emptyList())
        cache.clear()

        assertNull(cache.load(1))
    }
}
