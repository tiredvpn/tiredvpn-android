package com.tiredvpn.android.util

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CountryDetectorTest {

    /** Lookups the fake resolver was asked to perform, newest last. */
    private val asked = mutableListOf<String>()
    private var fakeNow = 1_000_000L

    @Before
    fun installFakes() {
        asked.clear()
        fakeNow = 1_000_000L
        CountryDetector.clock = { fakeNow }
        CountryDetector.clearCache()
    }

    @After
    fun removeFakes() {
        CountryDetector.resetTestSeams()
    }

    /** Answer every address with [code], counting the calls. */
    private fun answerWith(code: String?) {
        CountryDetector.lookup = { address ->
            asked += address
            code?.let { CountryDetector.getCountryInfo(it) }
        }
    }

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

    // --- cache ---
    //
    // The list adapter calls detectCountry() from every onBindViewHolder, and
    // notifyDataSetChanged() fires on every finished ping. Without a cache that
    // is one HTTPS request per row per repaint, which is what these tests pin.

    @Test
    fun `detectCountry asks the network once per address`() {
        answerWith("NL")

        val first = runBlocking { CountryDetector.detectCountry("1.2.3.4") }
        val second = runBlocking { CountryDetector.detectCountry("1.2.3.4") }

        assertEquals(listOf("1.2.3.4"), asked)
        assertEquals("NL", first.code)
        assertEquals("NL", second.code)
    }

    @Test
    fun `cache keeps addresses apart`() {
        CountryDetector.lookup = { address ->
            asked += address
            CountryDetector.getCountryInfo(if (address == "1.2.3.4") "NL" else "DE")
        }

        val nl = runBlocking { CountryDetector.detectCountry("1.2.3.4") }
        val de = runBlocking { CountryDetector.detectCountry("5.6.7.8") }
        val nlAgain = runBlocking { CountryDetector.detectCountry("1.2.3.4") }

        assertEquals(listOf("1.2.3.4", "5.6.7.8"), asked)
        assertEquals("NL", nl.code)
        assertEquals("DE", de.code)
        assertEquals("NL", nlAgain.code)
    }

    @Test
    fun `cached entry expires and is fetched again`() {
        answerWith("NL")

        runBlocking { CountryDetector.detectCountry("1.2.3.4") }
        fakeNow += CountryDetector.SUCCESS_TTL_MS - 1
        runBlocking { CountryDetector.detectCountry("1.2.3.4") }
        assertEquals("fresh entry must not be refetched", 1, asked.size)

        fakeNow += 2
        runBlocking { CountryDetector.detectCountry("1.2.3.4") }
        assertEquals("expired entry must be refetched", 2, asked.size)
    }

    @Test
    fun `failed lookup is remembered briefly, then retried`() {
        answerWith(null)

        val fallback = runBlocking { CountryDetector.detectCountry("gone.example") }
        assertEquals("XX", fallback.code)
        assertEquals("gone.example", fallback.name)

        runBlocking { CountryDetector.detectCountry("gone.example") }
        assertEquals("a failure must not be retried on every bind", 1, asked.size)

        fakeNow += CountryDetector.FAILURE_TTL_MS + 1
        runBlocking { CountryDetector.detectCountry("gone.example") }
        assertEquals(2, asked.size)

        // The two TTLs are read from the object above, so the steps alone would
        // still pass if a failure were pinned for the full six hours. Say it.
        assertTrue(
            "a failure must expire much sooner than a success",
            CountryDetector.FAILURE_TTL_MS < CountryDetector.SUCCESS_TTL_MS
        )
    }

    @Test
    fun `cached returns a successful answer without suspending`() {
        answerWith("NL")

        assertNull(CountryDetector.cached("1.2.3.4"))
        runBlocking { CountryDetector.detectCountry("1.2.3.4") }

        assertEquals("NL", CountryDetector.cached("1.2.3.4")?.code)
        assertEquals("a synchronous read must not trigger a lookup", 1, asked.size)
    }

    @Test
    fun `cached stays null for a remembered failure`() {
        answerWith(null)

        runBlocking { CountryDetector.detectCountry("gone.example") }

        assertNull(CountryDetector.cached("gone.example"))
    }
}
