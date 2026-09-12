package com.anomalyco.opencode.data.repository

import com.anomalyco.opencode.domain.error.OpenCodeError
import com.anomalyco.opencode.domain.error.OpenCodeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

/** Contract tests for the shared repository Result wrapper. */
class CallSupportTest {

    @Test
    fun `success passes the value through`() = runTest {
        assertEquals(42, apiCall { 42 }.getOrThrow())
    }

    @Test
    fun `raw transport failures are wrapped as OpenCodeException with a typed error`() = runTest {
        val result = apiCall<Int> { throw ConnectException("refused") }
        val failure = result.exceptionOrNull()
        assertTrue(failure is OpenCodeException)
        assertEquals(
            OpenCodeError.Network(OpenCodeError.NetworkKind.Connect),
            (failure as OpenCodeException).error,
        )
        assertEquals("refused", failure.cause?.message)
    }

    @Test
    fun `an already-typed OpenCodeException is never double-wrapped`() = runTest {
        val original = OpenCodeException(OpenCodeError.AuthRejected)
        val result = apiCall<Int> { throw original }
        assertSame(original, result.exceptionOrNull())
    }

    @Test
    fun `cancellation-like programmer errors still surface as Unexpected`() = runTest {
        val boom = IllegalStateException("bug")
        val result = apiCall<Int> { throw boom }
        assertEquals(OpenCodeError.Unexpected(boom), (result.exceptionOrNull() as OpenCodeException).error)
    }
}
