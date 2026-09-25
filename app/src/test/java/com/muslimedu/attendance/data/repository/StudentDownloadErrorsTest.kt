package com.muslimedu.attendance.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentDownloadErrorsTest {

    @Test
    fun `a route Laravel doesn't have counts as missing, whether it answers 404 or 405`() {
        // 405 is what a real device got: a GET-only catch-all route matched
        // the URL, so Laravel said "POST not supported" instead of "not found".
        assertTrue(isMissingEndpoint(404))
        assertTrue(isMissingEndpoint(405))
        assertTrue(isMissingEndpoint(501))
    }

    @Test
    fun `real errors from an existing endpoint are not treated as missing`() {
        assertFalse(isMissingEndpoint(401))
        assertFalse(isMissingEndpoint(403))
        assertFalse(isMissingEndpoint(422))
        assertFalse(isMissingEndpoint(500))
    }
}
