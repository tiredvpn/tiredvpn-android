package com.tiredvpn.android.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison [ApkSignatureGuard.verify] rests on.
 *
 * The PackageManager calls around it need a device; this is the decision itself,
 * which is where "accept" and "reject" are actually chosen.
 */
class ApkSignatureGuardTest {

    private val certA = byteArrayOf(1, 2, 3, 4)
    private val certB = byteArrayOf(9, 9, 9, 9)

    @Test
    fun `same certificate matches`() {
        assertTrue(ApkSignatureGuard.signersMatch(listOf(certA), listOf(certA)))
    }

    @Test
    fun `equal content in a different array instance matches`() {
        // The comparison must be over bytes, not over identity: the two arrays
        // come from two different PackageInfo objects and are never the same
        // instance in production.
        assertTrue(ApkSignatureGuard.signersMatch(listOf(certA), listOf(byteArrayOf(1, 2, 3, 4))))
    }

    @Test
    fun `different certificate is rejected`() {
        assertFalse(ApkSignatureGuard.signersMatch(listOf(certA), listOf(certB)))
    }

    @Test
    fun `unreadable signers are rejected rather than waved through`() {
        assertFalse(ApkSignatureGuard.signersMatch(emptyList(), listOf(certA)))
        assertFalse(ApkSignatureGuard.signersMatch(listOf(certA), emptyList()))
        assertFalse(ApkSignatureGuard.signersMatch(emptyList(), emptyList()))
    }

    @Test
    fun `an extra signer on the downloaded apk is rejected`() {
        assertFalse(ApkSignatureGuard.signersMatch(listOf(certA), listOf(certA, certB)))
    }

    @Test
    fun `a dropped signer is rejected`() {
        assertFalse(ApkSignatureGuard.signersMatch(listOf(certA, certB), listOf(certA)))
    }

    @Test
    fun `multiple signers match regardless of order`() {
        assertTrue(ApkSignatureGuard.signersMatch(listOf(certA, certB), listOf(certB, certA)))
    }
}
