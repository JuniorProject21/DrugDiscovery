package com.inhibitai.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The drawings kept on the device. Nothing here touches Android: the cache is handed a folder,
 * so it can be exercised on a temporary one.
 */
class StructureCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun cache(maxBytes: Long = 1024) = StructureCache(File(folder.root, "structures"), maxBytes)

    private fun png(size: Int, fill: Byte = 7) = ByteArray(size) { fill }

    @Test
    fun `what was written comes back`() = runTest {
        val cache = cache()
        val bytes = png(64)
        cache.write("c1ccccc1|72|66|false|", bytes)

        assertArrayEquals(bytes, cache.read("c1ccccc1|72|66|false|"))
    }

    @Test
    fun `a drawing that was never stored reads as absent`() = runTest {
        assertNull(cache().read("never-asked-for"))
    }

    @Test
    fun `the same structure at another size is a separate drawing`() = runTest {
        val cache = cache()
        cache.write("c1ccccc1|72|66|false|", png(32, 1))
        cache.write("c1ccccc1|300|300|false|", png(32, 2))

        assertEquals(1.toByte(), cache.read("c1ccccc1|72|66|false|")!!.first())
        assertEquals(2.toByte(), cache.read("c1ccccc1|300|300|false|")!!.first())
    }

    @Test
    fun `the light and dark drawings don't overwrite each other`() = runTest {
        val cache = cache()
        cache.write("c1ccccc1|72|66|false|", png(32, 1))
        cache.write("c1ccccc1|72|66|true|", png(32, 2))

        assertEquals(1.toByte(), cache.read("c1ccccc1|72|66|false|")!!.first())
        assertEquals(2.toByte(), cache.read("c1ccccc1|72|66|true|")!!.first())
    }

    @Test
    fun `a SMILES with slashes and brackets is still a valid file name`() = runTest {
        val cache = cache()
        val key = """C/C=C\C(=O)N1CC[C@@H](N)C1|72|66|false|3,4,5"""
        cache.write(key, png(16))

        assertArrayEquals(png(16), cache.read(key))
    }

    @Test
    fun `the folder is kept under its limit`() = runTest {
        val cache = cache(maxBytes = 500)
        repeat(10) { i -> cache.write("structure-$i", png(100)) }

        assertTrue("1000 bytes written into a 500 byte cache", cache.size() <= 500)
    }

    @Test
    fun `the drawing used most recently survives eviction`() = runTest {
        val cache = cache(maxBytes = 300)
        cache.write("old", png(100, 1))
        cache.write("middle", png(100, 2))
        // Reading it makes it the most recently used, so the next write should not drop it.
        Thread.sleep(1100) // file timestamps have one-second resolution on some file systems
        cache.read("old")

        cache.write("new-1", png(100, 3))
        cache.write("new-2", png(100, 4))

        assertTrue("the one that was just used should still be there", cache.read("old") != null)
    }

    @Test
    fun `an empty drawing is not stored`() = runTest {
        val cache = cache()
        cache.write("empty", ByteArray(0))

        assertNull(cache.read("empty"))
        assertEquals(0L, cache.size())
    }

    @Test
    fun `clearing removes every drawing`() = runTest {
        val cache = cache()
        cache.write("a", png(32))
        cache.write("b", png(32))

        cache.clear()

        assertNull(cache.read("a"))
        assertEquals(0L, cache.size())
    }

    @Test
    fun `a half-written file is never read back`() = runTest {
        val cache = cache()
        cache.write("a", png(32))
        val partials = File(folder.root, "structures").listFiles()?.filter { it.name.endsWith(".part") }

        assertEquals("the temporary file should have been moved into place", emptyList<File>(), partials)
    }
}
