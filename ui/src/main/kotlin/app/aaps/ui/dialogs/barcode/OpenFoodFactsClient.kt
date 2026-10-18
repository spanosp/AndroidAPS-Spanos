package app.aaps.ui.dialogs.barcode

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal, blocking Open Food Facts lookup. Call from a background thread.
 * Uses the public read API (no key needed). Only the barcode is sent.
 */
object OpenFoodFactsClient {

    private const val FIELDS =
        "product_name,product_name_el,product_name_en,brands,nutriments,serving_quantity,serving_quantity_unit"

    // Open Food Facts asks API users to identify their app in the User-Agent.
    private const val USER_AGENT = "AndroidAPS-fork-barcode/1.0 (personal use)"
    private const val TIMEOUT_MS = 10_000

    fun lookup(barcode: String): LookupResult {
        if (!OpenFoodFactsParser.isValidBarcode(barcode)) return LookupResult.NotFound(barcode)
        return try {
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=$FIELDS")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK        ->
                        OpenFoodFactsParser.parse(barcode, connection.inputStream.bufferedReader().use { it.readText() })

                    HttpURLConnection.HTTP_NOT_FOUND -> LookupResult.NotFound(barcode)
                    else                             -> LookupResult.Error(IOException("HTTP $code"))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            LookupResult.Error(e)
        }
    }
}
