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

fun testFormatPlanDates() {
    val noon = { d: String -> java.time.Instant.parse("${d}T12:00:00Z") }
    assertEquals("Started Sep 25, 2026", larder.api.formatPlanDates(noon("2026-09-25"), null))
    assertEquals("Sep 25, 2026", larder.api.formatPlanDates(noon("2026-09-25"), noon("2026-09-25")))
    assertEquals("Sep 18 – Sep 25, 2026", larder.api.formatPlanDates(noon("2026-09-18"), noon("2026-09-25")))
    assertEquals("Dec 28, 2025 – Jan 3, 2026", larder.api.formatPlanDates(noon("2025-12-28"), noon("2026-01-03")))
}
