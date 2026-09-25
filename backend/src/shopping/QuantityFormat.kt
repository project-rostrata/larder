package larder.shopping


// "4 1/8", "3/8", "2" -- mixed numbers, the way recipes write quantities.
fun formatQuantity(q: Rational): String {
    val whole = q.num.divide(q.den)
    val rem = q.num.mod(q.den)
    return when {
        rem.signum() == 0 -> "$whole"
        whole.signum() == 0 -> "$rem/${q.den}"
        else -> "$whole $rem/${q.den}"
    }
}

fun pluralizeUnit(name: String): String =
    if (listOf("ch", "sh", "s", "x").any { name.endsWith(it) }) "${name}es" else "${name}s"

// "4 1/8 cups", "1 clove", "3" (bare count, no unit).
fun formatAmount(q: Rational, unitName: String?): String {
    val n = formatQuantity(q)
    if (unitName == null) return n
    val plural = q > Rational.ONE
    return "$n ${if (plural) pluralizeUnit(unitName) else unitName}"
}

