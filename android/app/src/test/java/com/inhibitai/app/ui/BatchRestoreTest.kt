package com.inhibitai.app.ui

import androidx.test.core.app.ApplicationProvider
import com.inhibitai.app.FakeBackend
import com.inhibitai.app.batchResult
import com.inhibitai.app.data.BatchCache
import com.inhibitai.app.user
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screening run is the most expensive thing the app asks for, and Android may kill a
 * backgrounded app at any moment. A finished run has to be there when the user comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchRestoreTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var backend: FakeBackend

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        backend = FakeBackend().apply { user = user() }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = InhibitViewModel(ApplicationProvider.getApplicationContext(), backend)

    @Test
    fun `a finished run is on screen again after the app is killed`() = runTest(dispatcher) {
        // What the device is holding when the app starts: the app was killed after the run.
        backend.savedBatch = BatchCache.Saved(
            result = batchResult("library.csv"),
            warnings = listOf("Line 4 has no SMILES, so it was skipped."),
            savedAt = 1_763_000_000_000,
        )

        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.batchState
        assertTrue("the run should be back on the Batch screen", state is BatchState.Done)
        state as BatchState.Done
        assertEquals("library.csv", state.result.fileName)
        assertEquals(3, state.result.total)
        assertEquals(2, state.result.succeeded)
        assertEquals(listOf("Line 4 has no SMILES, so it was skipped."), state.warnings)
    }

    @Test
    fun `with nothing stored the Batch screen starts empty`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(BatchState.Idle, vm.batchState)
    }

    @Test
    fun `dismissing a run forgets it on the device too`() = runTest(dispatcher) {
        backend.savedBatch = BatchCache.Saved(batchResult(), emptyList(), 1_763_000_000_000)
        val vm = viewModel()
        advanceUntilIdle()

        vm.clearBatch()
        advanceUntilIdle()

        assertEquals(BatchState.Idle, vm.batchState)
        assertNull("it must not come back on the next launch", backend.savedBatch)
    }

    @Test
    fun `a run belonging to a signed-out account is not restored`() = runTest(dispatcher) {
        // Nobody is signed in, so there is no account to restore a run for.
        backend.user = null
        backend.savedBatch = BatchCache.Saved(batchResult(), emptyList(), 1_763_000_000_000)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(AuthState.SignedOut, vm.authState)
        assertEquals(BatchState.Idle, vm.batchState)
    }
}
