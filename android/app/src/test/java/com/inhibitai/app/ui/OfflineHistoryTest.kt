package com.inhibitai.app.ui

import androidx.test.core.app.ApplicationProvider
import com.inhibitai.app.FakeBackend
import com.inhibitai.app.compound
import com.inhibitai.app.data.ApiException
import com.inhibitai.app.data.HistoryCache
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the app shows when the server can't be reached. The promise is that it opens with the
 * predictions saved on the device and says so, rather than an empty screen and an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineHistoryTest {

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
    fun `with no server, the device's own copy is shown`() = runTest(dispatcher) {
        backend.cached = HistoryCache.Snapshot(
            compounds = listOf(compound("1", "Olaparib"), compound("2", "Aspirin")),
            savedAt = 1_763_000_000_000,
        )
        backend.historyFailure = ApiException("Can't reach the server.", isOffline = true)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(2, vm.compounds.size)
        assertNull("an offline app with results to show isn't an error state", vm.loadError)
        assertNotNull(vm.offlineNotice)
        assertTrue(vm.offlineNotice.orEmpty().startsWith("Offline:"))
    }

    @Test
    fun `with no server and nothing saved, the failure is reported`() = runTest(dispatcher) {
        backend.historyFailure = ApiException("Can't reach the server.", isOffline = true)

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.compounds.isEmpty())
        assertEquals("Can't reach the server.", vm.loadError)
        assertNull(vm.offlineNotice)
    }

    @Test
    fun `a successful refresh clears the offline banner`() = runTest(dispatcher) {
        backend.cached = HistoryCache.Snapshot(listOf(compound("1")), savedAt = 1_763_000_000_000)
        backend.historyFailure = ApiException("Can't reach the server.", isOffline = true)
        val vm = viewModel()
        advanceUntilIdle()
        assertNotNull(vm.offlineNotice)

        backend.historyFailure = null
        backend.stored += listOf(compound("1"), compound("2"), compound("3"))
        vm.refresh()
        advanceUntilIdle()

        assertEquals(3, vm.compounds.size)
        assertNull("what's on screen is live again", vm.offlineNotice)
    }

    @Test
    fun `an expired session signs the user out`() = runTest(dispatcher) {
        backend.historyFailure = ApiException("Please sign in again.", code = 401)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(AuthState.SignedOut, vm.authState)
    }
}
