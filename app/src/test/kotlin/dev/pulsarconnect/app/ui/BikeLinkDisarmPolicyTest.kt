package dev.pulsarconnect.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeLinkDisarmPolicyTest {

    @Test
    fun `app loading never clears the saved mirroring preference`() {
        assertFalse(shouldDisarmBikeLink(UiState.Loading))
    }

    @Test
    fun `signing in keeps the saved mirroring preference`() {
        assertFalse(shouldDisarmBikeLink(UiState.SignedIn(vin = "MD2ABCDEFGH123456", bikeName = null)))
    }

    @Test
    fun `signing out still disarms the bike link`() {
        assertTrue(shouldDisarmBikeLink(UiState.SignedOut()))
    }
}
