package com.inhibitai.app.ui

import androidx.test.core.app.ApplicationProvider
import com.inhibitai.app.FakeBackend
import com.inhibitai.app.compound
import com.inhibitai.app.data.ApiException
import com.inhibitai.app.user
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting a prediction holds the server call back for the length of the undo window. These
 * tests pin that down: the row leaves the list at once, the server is told only when the window
 * closes, and Undo in between means the server is never told at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndoDeleteTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: FakeBackend

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = FakeBackend().apply {
            user = user()
            stored += listOf(compound("1", "Olaparib"), compound("2", "Aspirin"), compound("3", "21F"))
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = InhibitViewModel(ApplicationProvider.getApplicationContext(), backend)

    @Test
    fun `deleted row leaves the list before the server is told`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(3, vm.compounds.size)

        vm.delete("2")

        assertEquals(listOf("1", "3"), vm.compounds.map { it.id })
        assertEquals("nothing should have reached the server yet", emptyList<String>(), backend.deleted)
        assertNotNull("the undo bar needs something to show", vm.pendingDelete)
        assertEquals("Deleted Aspirin", vm.pendingDelete?.label)
    }

    @Test
    fun `undo puts the row back where it was and never calls the server`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.delete("2")
        vm.undoDelete()

        assertEquals("the row belongs in its old place, not at the top",
            listOf("1", "2", "3"), vm.compounds.map { it.id })
        assertNull(vm.pendingDelete)

        // Past the window: the cancelled delete must not fire late.
        advanceTimeBy(UNDO_WINDOW_MS * 2)
        runCurrent()
        assertEquals(emptyList<String>(), backend.deleted)
    }

    @Test
    fun `the delete reaches the server once the window closes`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.delete("2")
        advanceTimeBy(UNDO_WINDOW_MS - 100)
        runCurrent()
        assertEquals("still inside the window", emptyList<String>(), backend.deleted)

        advanceTimeBy(200)
        advanceUntilIdle()
        assertEquals(listOf("2"), backend.deleted)
        assertNull(vm.pendingDelete)
    }

    @Test
    fun `deleting several rows is one undo for the whole batch`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.delete(listOf("1", "3"))

        assertEquals(listOf("2"), vm.compounds.map { it.id })
        assertEquals("Deleted 2 predictions", vm.pendingDelete?.label)

        vm.undoDelete()
        assertEquals(listOf("1", "2", "3"), vm.compounds.map { it.id })
        assertEquals(emptyList<String>(), backend.deleted)
    }

    @Test
    fun `a refresh sends the waiting delete first, so the row cannot come back`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.delete("2")
        vm.refresh()
        advanceUntilIdle()

        assertEquals("the server should have been told before the list was re-read",
            listOf("2"), backend.deleted)
        assertEquals(listOf("1", "3"), vm.compounds.map { it.id })
    }

    @Test
    fun `a delete the server refuses puts the row back and says so`() = runTest(dispatcher) {
        backend.deleteFailure = ApiException("Server error (500).", code = 500)
        val vm = viewModel()
        advanceUntilIdle()

        vm.delete("2")
        advanceTimeBy(UNDO_WINDOW_MS + 100)
        advanceUntilIdle()

        assertEquals("the prediction still exists, so it belongs on the list",
            listOf("1", "2", "3"), vm.compounds.map { it.id })
        assertTrue(vm.message.orEmpty().contains("Couldn't delete"))
    }
}
