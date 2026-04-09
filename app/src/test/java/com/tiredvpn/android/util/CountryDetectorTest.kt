package com.tiredvpn.android.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CountryDetectorTest {

    // --- countryCodeToFlag ---

    @Test
    fun `countryCodeToFlag returns non-empty string for US`() {
        val flag = CountryDetector.countryCodeToFlag("US")
        assertTrue(flag.isNotEmpty())
    }

    @Test
    fun `countryCodeToFlag is case-insensitive`() {
        assertEquals(
            CountryDetector.countryCodeToFlag("RU"),
            CountryDetector.countryCodeToFlag("ru")
        )
    }

    @Test
    fun `countryCodeToFlag returns globe emoji for invalid length`() {
        assertEquals("\uD83C\uDF10", CountryDetector.countryCodeToFlag("USA"))
        assertEquals("\uD83C\uDF10", CountryDetector.countryCodeToFlag("X"))
        assertEquals("\uD83C\uDF10", CountryDetector.countryCodeToFlag(""))
    }

    // --- getCountryName ---

    @Test
    fun `getCountryName returns known names`() {
        assertEquals("Russia", CountryDetector.getCountryName("RU"))
        assertEquals("United States", CountryDetector.getCountryName("US"))
        assertEquals("Netherlands", CountryDetector.getCountryName("NL"))
        assertEquals("Germany", CountryDetector.getCountryName("DE"))
    }

    @Test
    fun `getCountryName is case-insensitive`() {
        assertEquals("Russia", CountryDetector.getCountryName("ru"))
        assertEquals("United States", CountryDetector.getCountryName("us"))
    }

    @Test
    fun `getCountryName returns code itself for unknown`() {
        assertEquals("ZZ", CountryDetector.getCountryName("ZZ"))
    }

    // --- getCountryInfo ---

    @Test
    fun `getCountryInfo constructs proper CountryInfo`() {
        val info = CountryDetector.getCountryInfo("nl")

        assertEquals("NL", info.code)
        assertEquals("Netherlands", info.name)
        assertTrue(info.flag.isNotEmpty())
        assertEquals(CountryDetector.countryCodeToFlag("NL"), info.flag)
    }
}
