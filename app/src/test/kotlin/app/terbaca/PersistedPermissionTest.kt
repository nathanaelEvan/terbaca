package app.terbaca

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersistedPermissionTest {

    @Test
    fun alreadyPersisted_onSuccess_doesNotPersistOrRelease() = runTest {
        var persistCalled = false
        var releaseCalled = false

        val result = withPersistedPermission(
            recordInRecents = true,
            isAlreadyPersisted = { true },
            persistPermission = {
                persistCalled = true
                true
            },
            releasePermission = { releaseCalled = true }
        ) { persisted ->
            assertTrue("Expected persisted to be true", persisted)
            "success"
        }

        assertEquals("success", result)
        assertFalse("Should not call persistPermission if already persisted", persistCalled)
        assertFalse("Should not call releasePermission on success", releaseCalled)
    }

    @Test
    fun alreadyPersisted_onException_leavesPermissionInPlace() = runTest {
        var releaseCalled = false

        try {
            withPersistedPermission(
                recordInRecents = true,
                isAlreadyPersisted = { true },
                persistPermission = { true },
                releasePermission = { releaseCalled = true }
            ) {
                throw IOException("Simulated I/O failure")
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Simulated I/O failure", e.message)
        }

        assertFalse("Existing persisted permission must NOT be released on failure", releaseCalled)
    }

    @Test
    fun alreadyPersisted_onCancellation_leavesPermissionInPlace() = runTest {
        var releaseCalled = false

        try {
            withPersistedPermission(
                recordInRecents = true,
                isAlreadyPersisted = { true },
                persistPermission = { true },
                releasePermission = { releaseCalled = true }
            ) {
                throw CancellationException("Simulated cancellation (e.g. Back pressed)")
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Simulated cancellation (e.g. Back pressed)", e.message)
        }

        assertFalse("Existing persisted permission must NOT be released on cancellation", releaseCalled)
    }

    @Test
    fun newlyPersisted_onSuccess_doesNotRelease() = runTest {
        var persistCalled = false
        var releaseCalled = false

        val result = withPersistedPermission(
            recordInRecents = true,
            isAlreadyPersisted = { false },
            persistPermission = {
                persistCalled = true
                true
            },
            releasePermission = { releaseCalled = true }
        ) { persisted ->
            assertTrue(persisted)
            42
        }

        assertEquals(42, result)
        assertTrue("persistPermission should be called", persistCalled)
        assertFalse("releasePermission should not be called on success", releaseCalled)
    }

    @Test
    fun newlyPersisted_onException_releasesPermission() = runTest {
        var persistCalled = false
        var releaseCalled = false

        try {
            withPersistedPermission(
                recordInRecents = true,
                isAlreadyPersisted = { false },
                persistPermission = {
                    persistCalled = true
                    true
                },
                releasePermission = { releaseCalled = true }
            ) {
                throw IOException("Corrupt PDF descriptor")
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Corrupt PDF descriptor", e.message)
        }

        assertTrue(persistCalled)
        assertTrue("Newly persisted permission must be released on failure", releaseCalled)
    }

    @Test
    fun newlyPersisted_onCancellation_leavesPermissionInPlace() = runTest {
        var persistCalled = false
        var releaseCalled = false

        try {
            withPersistedPermission(
                recordInRecents = true,
                isAlreadyPersisted = { false },
                persistPermission = {
                    persistCalled = true
                    true
                },
                releasePermission = { releaseCalled = true }
            ) {
                throw CancellationException("Cancelled while loading")
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Cancelled while loading", e.message)
        }

        assertTrue(persistCalled)
        assertFalse("Newly persisted permission must NOT be released on cancellation", releaseCalled)
    }

    @Test
    fun recordInRecentsFalse_onException_doesNotPersistOrRelease() = runTest {
        var persistCalled = false
        var releaseCalled = false

        try {
            withPersistedPermission(
                recordInRecents = false,
                isAlreadyPersisted = { false },
                persistPermission = {
                    persistCalled = true
                    true
                },
                releasePermission = { releaseCalled = true }
            ) { persisted ->
                assertFalse(persisted)
                throw IOException("Failed")
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Failed", e.message)
        }

        assertFalse(persistCalled)
        assertFalse(releaseCalled)
    }

    @Test
    fun persistFails_onException_doesNotRelease() = runTest {
        var releaseCalled = false

        try {
            withPersistedPermission(
                recordInRecents = true,
                isAlreadyPersisted = { false },
                persistPermission = { false },
                releasePermission = { releaseCalled = true }
            ) { persisted ->
                assertFalse(persisted)
                throw IOException("Failed")
            }
            fail("Expected IOException")
        } catch (e: IOException) {
            assertEquals("Failed", e.message)
        }

        assertFalse(releaseCalled)
    }
}
