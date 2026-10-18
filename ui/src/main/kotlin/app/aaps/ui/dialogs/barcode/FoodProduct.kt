package app.aaps.ui.dialogs.barcode

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Nutrition data for a scanned food product.
 * Values are per 100 g (or 100 ml for drinks), as reported by Open Food Facts.
 */
data class FoodProduct(
    val barcode: String,
    val name: String,
    val carbsPer100g: Double,
    val sugarsPer100g: Double?,
    /** Serving size in grams if the database has a usable value, otherwise null. */
    val servingGrams: Double?
) {

    /** True if the data looks internally inconsistent and the user should check the label. */
    val sugarsExceedCarbs: Boolean
        get() = sugarsPer100g != null && sugarsPer100g > carbsPer100g + 0.5

    /** Carbs (rounded to whole grams, as AAPS carb entries are integers) for the given amount. */
    fun carbsFor(grams: Double): Int = carbsForAmount(carbsPer100g, grams)

    companion object {

        fun carbsForAmount(carbsPer100g: Double, grams: Double): Int =
            if (grams <= 0.0 || carbsPer100g <= 0.0) 0 else (carbsPer100g * grams / 100.0).roundToInt()
    }
}

sealed class LookupResult {
    data class Found(val product: FoodProduct) : LookupResult()
    data class NotFound(val barcode: String) : LookupResult()
    data class NoCarbData(val name: String) : LookupResult()
    data class InvalidCarbData(val name: String, val value: Double) : LookupResult()
    data class Error(val throwable: Throwable) : LookupResult()
}

object OpenFoodFactsParser {

    /** EAN-8, UPC-A (12), EAN-13 and GTIN-14 are digits only. Anything else is rejected before any network call. */
    private val BARCODE = Regex("^\\d{8,14}$")

    /** Max plausible serving size; larger values in the database are usually unit mistakes. */
    private const val MAX_SERVING_GRAMS = 2000.0

    fun isValidBarcode(code: String?): Boolean = code != null && BARCODE.matches(code)

    fun parse(barcode: String, json: String): LookupResult {
        val root = JSONObject(json)
        val product = root.optJSONObject("product")
        if (root.optInt("status", 0) != 1 || product == null) return LookupResult.NotFound(barcode)

        val name = productName(product, barcode)
        val nutriments = product.optJSONObject("nutriments") ?: return LookupResult.NoCarbData(name)

        val carbs = number(nutriments, "carbohydrates_100g") ?: return LookupResult.NoCarbData(name)
        if (carbs.isNaN() || carbs < 0.0 || carbs > 100.0) return LookupResult.InvalidCarbData(name, carbs)

        val serving = number(product, "serving_quantity")?.takeIf { it > 0.0 && it <= MAX_SERVING_GRAMS }
            ?.takeIf { servingUnitIsGramsOrMl(product) }

        return LookupResult.Found(
            FoodProduct(
                barcode = barcode,
                name = name,
                carbsPer100g = carbs,
                sugarsPer100g = number(nutriments, "sugars_100g")?.takeIf { it >= 0.0 },
                servingGrams = serving
            )
        )
    }

    private fun productName(product: JSONObject, barcode: String): String {
        val base = listOf("product_name", "product_name_el", "product_name_en")
            .map { product.optString(it, "").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: barcode
        val brand = product.optString("brands", "").split(',').firstOrNull()?.trim().orEmpty()
        return if (brand.isNotEmpty() && !base.contains(brand, ignoreCase = true)) "$brand $base" else base
    }

    /** serving_quantity_unit is missing on older entries; in that case Open Food Facts reports grams. */
    private fun servingUnitIsGramsOrMl(product: JSONObject): Boolean {
        val unit = product.optString("serving_quantity_unit", "g").trim().lowercase()
        return unit.isEmpty() || unit == "g" || unit == "ml"
    }

    /** Open Food Facts sometimes returns numbers as strings, e.g. "12.5" or "12,5". */
    private fun number(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val v = obj.get(key)) {
            is Number -> v.toDouble()
            is String -> v.trim().replace(',', '.').toDoubleOrNull()
            else      -> null
        }
    }
}
