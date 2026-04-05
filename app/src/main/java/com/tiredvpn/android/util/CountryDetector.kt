package com.tiredvpn.android.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            try {
                Log.d(TAG, "Trying API: $url")
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000  // 5 sec
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()

                Log.d(TAG, "Response: $response")
                val result = parser(response)
                if (result != null) {
                    Log.d(TAG, "Detected: ${result.name} (${result.flag})")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "API failed: $url - ${e.message}")
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
    suspend fun detectCountryFromHost(host: String): CountryInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Resolving hostname: $host")
            val ip = InetAddress.getByName(host).hostAddress ?: return@withContext null
            Log.d(TAG, "Resolved to IP: $ip")
            detectCountryFromIP(ip)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve $host: ${e.message}")
            null
        }
    }

    /**
     * Try to detect country from server address (IP or hostname)
     * Falls back to showing the server address if detection fails
     */
    suspend fun detectCountry(serverAddress: String): CountryInfo {
        Log.d(TAG, "detectCountry: $serverAddress")

        // Check if it's already an IP
        val isIP = serverAddress.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))

        val result = if (isIP) {
            detectCountryFromIP(serverAddress)
        } else {
            detectCountryFromHost(serverAddress)
        }

        return result ?: CountryInfo(
            code = "XX",
            name = serverAddress,  // Show server address as fallback
            flag = "🌐"
        )
    }
}
