import kotlin.test.assertEquals
import larder.api.formatMinutes
import larder.api.formatNumber
import larder.api.formatServings
import java.math.BigDecimal

fun testFormatMinutes() {
    assertEquals(null, formatMinutes(null))
    assertEquals("0 min", formatMinutes(0))
    assertEquals("45 min", formatMinutes(45))
    assertEquals("1 hr", formatMinutes(60))
    assertEquals("1 hr 15 min", formatMinutes(75))
}

fun testFormatNumberTrimsAndRounds() {
    assertEquals("4", formatNumber(BigDecimal("4.0")))
    assertEquals("1.5", formatNumber(BigDecimal("1.50")))
    assertEquals("6.67", formatNumber(BigDecimal("6.666666")))
    assertEquals("100", formatNumber(BigDecimal("1E+2")))
}

fun testFormatServingsSingularAndPlural() {
    assertEquals("1 serving", formatServings(BigDecimal("1.00")))
    assertEquals("6 servings", formatServings(BigDecimal("4").multiply(BigDecimal("1.5"))))
}
