package com.tiredvpn.android.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

private const val TAG = "CountryDetector"

/**
 * Utility for detecting country from IP address and converting to emoji flags
 */
object CountryDetector {

    data class CountryInfo(
        val code: String,      // ISO 3166-1 alpha-2 (e.g., "US")
        val name: String,      // Full name (e.g., "United States")
        val flag: String       // Emoji flag (e.g., "🇺🇸")
    )

    /** How long a successful lookup is reused. Server locations don't move. */
    internal const val SUCCESS_TTL_MS = 6L * 60 * 60 * 1000

    /**
     * How long a failure is remembered. Short, because a failure is usually a
     * dead network rather than a property of the address - but not zero, or a
     * list of unreachable servers fires one request per row per repaint.
     */
    internal const val FAILURE_TTL_MS = 5L * 60 * 1000

    private const val CACHE_MAX_ENTRIES = 128
    private const val HTTP_TIMEOUT_MS = 5000
    private const val RESOLVE_TIMEOUT_MS = 5000L

    /** Test seams: a fake clock and a fake network, so tests never dial out. */
    internal var clock: () -> Long = System::currentTimeMillis
    internal var lookup: suspend (String) -> CountryInfo? = { address -> lookupRemote(address) }

    internal fun resetTestSeams() {
        clock = System::currentTimeMillis
        lookup = { address -> lookupRemote(address) }
        clearCache()
    }

    private class Entry(val info: CountryInfo?, val storedAt: Long)

    /** address -> last answer. Access only under its own lock. */
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > CACHE_MAX_ENTRIES
    }

    internal fun clearCache() = synchronized(cache) { cache.clear() }

    private fun peek(address: String): Entry? = synchronized(cache) {
        val entry = cache[address] ?: return null
        val ttl = if (entry.info != null) SUCCESS_TTL_MS else FAILURE_TTL_MS
        if (clock() - entry.storedAt > ttl) {
            cache.remove(address)
            return null
        }
        entry
    }

    private fun store(address: String, info: CountryInfo?) = synchronized(cache) {
        cache[address] = Entry(info, clock())
        Unit
    }

    /**
     * The answer already in hand for [serverAddress], or null when nothing was
     * looked up yet (or the last lookup failed). Lets a list row paint its flag
     * without starting a coroutine on every rebind.
     */
    fun cached(serverAddress: String): CountryInfo? = peek(serverAddress)?.info

    // Convert ISO 3166-1 alpha-2 country code to emoji flag
    fun countryCodeToFlag(countryCode: String): String {
        if (countryCode.length != 2) return "🌐"
        val code = countryCode.uppercase()
        val firstChar = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

    // Country code to name mapping
    private val countryNames = mapOf(
        "AF" to "Afghanistan", "AL" to "Albania", "DZ" to "Algeria", "AD" to "Andorra",
        "AO" to "Angola", "AR" to "Argentina", "AM" to "Armenia", "AU" to "Australia",
        "AT" to "Austria", "AZ" to "Azerbaijan", "BS" to "Bahamas", "BH" to "Bahrain",
        "BD" to "Bangladesh", "BY" to "Belarus", "BE" to "Belgium", "BZ" to "Belize",
        "BJ" to "Benin", "BT" to "Bhutan", "BO" to "Bolivia", "BA" to "Bosnia",
        "BW" to "Botswana", "BR" to "Brazil", "BN" to "Brunei", "BG" to "Bulgaria",
        "KH" to "Cambodia", "CM" to "Cameroon", "CA" to "Canada", "CL" to "Chile",
        "CN" to "China", "CO" to "Colombia", "CR" to "Costa Rica", "HR" to "Croatia",
        "CU" to "Cuba", "CY" to "Cyprus", "CZ" to "Czech Republic", "DK" to "Denmark",
        "EC" to "Ecuador", "EG" to "Egypt", "SV" to "El Salvador", "EE" to "Estonia",
        "ET" to "Ethiopia", "FI" to "Finland", "FR" to "France", "GE" to "Georgia",
        "DE" to "Germany", "GH" to "Ghana", "GR" to "Greece", "GT" to "Guatemala",
        "HN" to "Honduras", "HK" to "Hong Kong", "HU" to "Hungary", "IS" to "Iceland",
        "IN" to "India", "ID" to "Indonesia", "IR" to "Iran", "IQ" to "Iraq",
        "IE" to "Ireland", "IL" to "Israel", "IT" to "Italy", "JM" to "Jamaica",
        "JP" to "Japan", "JO" to "Jordan", "KZ" to "Kazakhstan", "KE" to "Kenya",
        "KW" to "Kuwait", "KG" to "Kyrgyzstan", "LA" to "Laos", "LV" to "Latvia",
        "LB" to "Lebanon", "LY" to "Libya", "LT" to "Lithuania", "LU" to "Luxembourg",
        "MO" to "Macau", "MK" to "North Macedonia", "MY" to "Malaysia", "MV" to "Maldives",
        "MT" to "Malta", "MX" to "Mexico", "MD" to "Moldova", "MC" to "Monaco",
        "MN" to "Mongolia", "ME" to "Montenegro", "MA" to "Morocco", "MZ" to "Mozambique",
        "MM" to "Myanmar", "NP" to "Nepal", "NL" to "Netherlands", "NZ" to "New Zealand",
        "NI" to "Nicaragua", "NG" to "Nigeria", "NO" to "Norway", "OM" to "Oman",
        "PK" to "Pakistan", "PA" to "Panama", "PY" to "Paraguay", "PE" to "Peru",
        "PH" to "Philippines", "PL" to "Poland", "PT" to "Portugal", "PR" to "Puerto Rico",
        "QA" to "Qatar", "RO" to "Romania", "RU" to "Russia", "SA" to "Saudi Arabia",
        "RS" to "Serbia", "SG" to "Singapore", "SK" to "Slovakia", "SI" to "Slovenia",
        "ZA" to "South Africa", "KR" to "South Korea", "ES" to "Spain", "LK" to "Sri Lanka",
        "SE" to "Sweden", "CH" to "Switzerland", "TW" to "Taiwan", "TJ" to "Tajikistan",
        "TZ" to "Tanzania", "TH" to "Thailand", "TR" to "Turkey", "TM" to "Turkmenistan",
        "UA" to "Ukraine", "AE" to "UAE", "GB" to "United Kingdom", "US" to "United States",
        "UY" to "Uruguay", "UZ" to "Uzbekistan", "VE" to "Venezuela", "VN" to "Vietnam",
        "YE" to "Yemen", "ZM" to "Zambia", "ZW" to "Zimbabwe"
    )

    fun getCountryName(code: String): String {
        return countryNames[code.uppercase()] ?: code
    }

    fun getCountryInfo(code: String): CountryInfo {
        val upperCode = code.uppercase()
        return CountryInfo(
            code = upperCode,
            name = getCountryName(upperCode),
            flag = countryCodeToFlag(upperCode)
        )
    }

    /**
     * Detect country from IP address using free IP geolocation API
     */
    suspend fun detectCountryFromIP(ip: String): CountryInfo? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Detecting country for IP: $ip")

        // Try multiple HTTPS APIs (HTTP is blocked on Android)
        val apis = listOf(
            "https://ipwho.is/$ip" to ::parseIpWhoIs,
            "https://ipapi.co/$ip/json/" to ::parseIpapiCo
        )

        for ((url, parser) in apis) {
            // disconnect() belongs in finally: a throw inside readText() used to
            // leave the socket open until the finalizer got to it.
            var connection: HttpURLConnection? = null
            try {
                Log.d(TAG, "Trying API: $url")
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    requestMethod = "GET"
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                Log.d(TAG, "Response: $response")
                val result = parser(response)
                if (result != null) {
                    Log.d(TAG, "Detected: ${result.name} (${result.flag})")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "API failed: $url - ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }

        Log.w(TAG, "All APIs failed for IP: $ip")
        null
    }

    private fun parseIpWhoIs(json: String): CountryInfo? {
        // Parse {"country_code":"NL","country":"Netherlands",...}
        val codeMatch = Regex(""""country_code"\s*:\s*"(\w+)"""").find(json)
        val nameMatch = Regex(""""country"\s*:\s*"([^"]+)"""").find(json)

        if (codeMatch != null && nameMatch != null) {
            val code = codeMatch.groupValues[1]
            val name = nameMatch.groupValues[1]
            return CountryInfo(code, name, countryCodeToFlag(code))
        }
        return null
    }

    private fun parseIpapiCo(json: String): CountryInfo? {
        // Parse {"country_code":"NL","country_name":"Netherlands",...}
        val codeMatch = Regex(""""country_code"\s*:\s*"(\w+)"""").find(json)
        val nameMatch = Regex(""""country_name"\s*:\s*"([^"]+)"""").find(json)

        if (codeMatch != null && nameMatch != null) {
            val code = codeMatch.groupValues[1]
            val name = nameMatch.groupValues[1]
            return CountryInfo(code, name, countryCodeToFlag(code))
        }
        return null
    }

    /**
     * Resolve hostname to IP and detect country
     */
    suspend fun detectCountryFromHost(host: String): CountryInfo? {
        val ip = resolveHost(host) ?: return null
        Log.d(TAG, "Resolved to IP: $ip")
        return detectCountryFromIP(ip)
    }

    /**
     * InetAddress.getByName() has no timeout of its own and ignores interrupts
     * on most Android resolvers, so a dead DNS server used to pin the calling
     * coroutine forever - including one started per visible list row. The
     * timeout doesn't stop the resolver thread, it stops us waiting on it.
     */
    private suspend fun resolveHost(host: String): String? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Resolving hostname: $host")
                    InetAddress.getByName(host).hostAddress
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve $host: ${e.message}")
                    null
                }
            }
        }

    /**
     * Try to detect country from server address (IP or hostname)
     * Falls back to showing the server address if detection fails.
     *
     * Answers are cached per address: the callers are list rows that rebind on
     * every repaint, and geolocation of a server address is about as stable as
     * facts get.
     */
    suspend fun detectCountry(serverAddress: String): CountryInfo {
        peek(serverAddress)?.let { return it.info ?: fallbackFor(serverAddress) }

        Log.d(TAG, "detectCountry: $serverAddress")
        val result = lookup(serverAddress)
        store(serverAddress, result)
        return result ?: fallbackFor(serverAddress)
    }

    private suspend fun lookupRemote(serverAddress: String): CountryInfo? {
        // Check if it's already an IP
        val isIP = serverAddress.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))
        return if (isIP) {
            detectCountryFromIP(serverAddress)
        } else {
            detectCountryFromHost(serverAddress)
        }
    }

    private fun fallbackFor(serverAddress: String) = CountryInfo(
        code = "XX",
        name = serverAddress,  // Show server address as fallback
        flag = "🌐"
    )
}
