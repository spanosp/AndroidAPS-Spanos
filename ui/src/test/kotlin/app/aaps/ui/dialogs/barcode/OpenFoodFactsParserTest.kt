package app.aaps.ui.dialogs.barcode

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OpenFoodFactsParserTest {

    private val code = "5201004021435"

    @Test
    fun validBarcodes() {
        assertThat(OpenFoodFactsParser.isValidBarcode("12345678")).isTrue()
        assertThat(OpenFoodFactsParser.isValidBarcode(code)).isTrue()
        assertThat(OpenFoodFactsParser.isValidBarcode("1234567")).isFalse()
        assertThat(OpenFoodFactsParser.isValidBarcode("123456789012345")).isFalse()
        assertThat(OpenFoodFactsParser.isValidBarcode("52010040214/..")).isFalse()
        assertThat(OpenFoodFactsParser.isValidBarcode("https://example.com")).isFalse()
        assertThat(OpenFoodFactsParser.isValidBarcode(null)).isFalse()
    }

    @Test
    fun parsesFoundProduct() {
        val json = """
            {"status":1,"product":{"product_name":"Greek Yogurt 2%","brands":"Fage,Total",
             "serving_quantity":170,"serving_quantity_unit":"g",
             "nutriments":{"carbohydrates_100g":3.0,"sugars_100g":3.0}}}
        """.trimIndent()
        val result = OpenFoodFactsParser.parse(code, json) as LookupResult.Found
        assertThat(result.product.name).isEqualTo("Fage Greek Yogurt 2%")
        assertThat(result.product.carbsPer100g).isEqualTo(3.0)
        assertThat(result.product.servingGrams).isEqualTo(170.0)
        assertThat(result.product.sugarsExceedCarbs).isFalse()
        assertThat(result.product.carbsFor(170.0)).isEqualTo(5) // 5.1 -> 5
    }

    @Test
    fun numbersAsStringsWithCommaAndFallbackName() {
        val json = """{"status":1,"product":{"product_name":"","product_name_el":"Παξιμάδια",
            "nutriments":{"carbohydrates_100g":"68,5"}}}"""
        val result = OpenFoodFactsParser.parse(code, json) as LookupResult.Found
        assertThat(result.product.name).isEqualTo("Παξιμάδια")
        assertThat(result.product.carbsPer100g).isEqualTo(68.5)
        assertThat(result.product.servingGrams).isNull()
    }

    @Test
    fun notFound() {
        assertThat(OpenFoodFactsParser.parse(code, """{"status":0,"status_verbose":"product not found"}"""))
            .isEqualTo(LookupResult.NotFound(code))
    }

    @Test
    fun missingCarbs() {
        val json = """{"status":1,"product":{"product_name":"Water","nutriments":{"energy_100g":0}}}"""
        assertThat(OpenFoodFactsParser.parse(code, json)).isEqualTo(LookupResult.NoCarbData("Water"))
        val noNutriments = """{"status":1,"product":{"product_name":"Water"}}"""
        assertThat(OpenFoodFactsParser.parse(code, noNutriments)).isEqualTo(LookupResult.NoCarbData("Water"))
    }

    @Test
    fun implausibleCarbsRejected() {
        val json = """{"status":1,"product":{"product_name":"Bad","nutriments":{"carbohydrates_100g":350}}}"""
        assertThat(OpenFoodFactsParser.parse(code, json)).isInstanceOf(LookupResult.InvalidCarbData::class.java)
        val negative = """{"status":1,"product":{"product_name":"Bad","nutriments":{"carbohydrates_100g":-1}}}"""
        assertThat(OpenFoodFactsParser.parse(code, negative)).isInstanceOf(LookupResult.InvalidCarbData::class.java)
    }

    @Test
    fun suspiciousServingIgnoredAndSugarWarning() {
        val json = """{"status":1,"product":{"product_name":"Juice","serving_quantity":25000,
            "nutriments":{"carbohydrates_100g":5,"sugars_100g":10}}}"""
        val product = (OpenFoodFactsParser.parse(code, json) as LookupResult.Found).product
        assertThat(product.servingGrams).isNull()
        assertThat(product.sugarsExceedCarbs).isTrue()

        val nonGramServing = """{"status":1,"product":{"product_name":"Pills","serving_quantity":2,"serving_quantity_unit":"pieces",
            "nutriments":{"carbohydrates_100g":5}}}"""
        assertThat((OpenFoodFactsParser.parse(code, nonGramServing) as LookupResult.Found).product.servingGrams).isNull()
    }

    @Test
    fun carbsCalculation() {
        assertThat(FoodProduct.carbsForAmount(60.0, 50.0)).isEqualTo(30)
        assertThat(FoodProduct.carbsForAmount(12.5, 330.0)).isEqualTo(41) // 41.25
        assertThat(FoodProduct.carbsForAmount(60.0, 0.0)).isEqualTo(0)
        assertThat(FoodProduct.carbsForAmount(60.0, -10.0)).isEqualTo(0)
    }
}
