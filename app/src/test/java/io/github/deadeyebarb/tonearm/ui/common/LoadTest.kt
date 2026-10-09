package io.github.deadeyebarb.tonearm.ui.common

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A screen's value: loaded, refreshed quietly, and what a refresh that fails leaves. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadTest {
    @Test
    fun `a refresh that fails keeps the value, says so once and stays stale until one works`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var down = false
            var loads = 0
            val vm = LoaderViewModel({ if (down) throw IOException("down") else ++loads })
            val failures = mutableListOf<Throwable>()
            val watching = launch { vm.refreshFailures.collect { failures += it } }
            advanceUntilIdle()
            assertEquals(Load.Ready(1), vm.state)
            assertNull(vm.stale)

            down = true
            vm.reload(silent = true)
            advanceUntilIdle()
            assertEquals(Load.Ready(1), vm.state)
            assertTrue(vm.stale is IOException)
            assertFalse(vm.refreshing)
            assertEquals(1, failures.size)

            down = false
            vm.reload(silent = true)
            advanceUntilIdle()
            assertEquals(Load.Ready(2), vm.state)
            assertNull(vm.stale)
            assertEquals(1, failures.size)
            watching.cancel()
            vm.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a first load that fails is a failure to retry, not a stale value`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var down = true
            val vm = LoaderViewModel({ if (down) throw IOException("down") else "here" })
            val failures = mutableListOf<Throwable>()
            val watching = launch { vm.refreshFailures.collect { failures += it } }
            advanceUntilIdle()
            assertTrue(vm.state is Load.Failed)
            assertNull(vm.stale)
            // Pulled while there's nothing to show: still the failure, not a message on top of it.
            vm.reload(silent = true)
            advanceUntilIdle()
            assertTrue(vm.state is Load.Failed)
            assertTrue(failures.isEmpty())

            down = false
            vm.reload()
            advanceUntilIdle()
            assertEquals(Load.Ready("here"), vm.state)
            watching.cancel()
            vm.viewModelScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
