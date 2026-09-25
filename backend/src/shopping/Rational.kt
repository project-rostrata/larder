package larder.shopping

import java.math.BigDecimal
import java.math.BigInteger

// Exact fraction arithmetic for shopping-list quantities. Unit conversion factors are stored as
// decimals (NUMERIC), and BigDecimal values are themselves exact rationals, so everything stays
// exact until the one deliberate rounding step (niceRound) when a mixed-unit total is displayed.
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {
    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)

        fun of(num: BigInteger, den: BigInteger): Rational {
            require(den.signum() != 0) { "zero denominator" }
            val sign = if (den.signum() < 0) BigInteger.ONE.negate() else BigInteger.ONE
            val g = num.gcd(den).takeIf { it.signum() != 0 } ?: BigInteger.ONE
            return Rational(num.multiply(sign).divide(g), den.multiply(sign).divide(g))
        }

        fun of(num: Long, den: Long = 1): Rational = of(BigInteger.valueOf(num), BigInteger.valueOf(den))

        fun of(value: BigDecimal): Rational =
            if (value.scale() <= 0) of(value.toBigIntegerExact(), BigInteger.ONE)
            else of(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))

        // Closest fraction with denominator <= maxDen (continued fractions). Used for servings
        // multipliers that arrive as doubles, e.g. 5/3 stored as 1.6666666666666667.
        fun approximate(value: BigDecimal, maxDen: Long = 100): Rational {
            val exact = of(value)
            if (exact.den <= BigInteger.valueOf(maxDen)) return exact
            var (p0, q0, p1, q1) = listOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
            var n = exact.num
            var d = exact.den
            val limit = BigInteger.valueOf(maxDen)
            while (d.signum() != 0) {
                val a = n.divide(d)
                val q2 = q0.add(a.multiply(q1))
                if (q2 > limit) break
                val p2 = p0.add(a.multiply(p1))
                p0 = p1; q0 = q1; p1 = p2; q1 = q2
                val r = n.subtract(a.multiply(d)); n = d; d = r
            }
            return of(p1, q1)
        }
    }

    operator fun plus(o: Rational) = of(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den))
    operator fun times(o: Rational) = of(num.multiply(o.num), den.multiply(o.den))
    operator fun div(o: Rational) = of(num.multiply(o.den), den.multiply(o.num))
    override fun compareTo(other: Rational) = num.multiply(other.den).compareTo(other.num.multiply(den))
    override fun equals(other: Any?) = other is Rational && num == other.num && den == other.den
    override fun hashCode() = 31 * num.hashCode() + den.hashCode()
    override fun toString() = if (den == BigInteger.ONE) "$num" else "$num/$den"

    // Keeps "kitchen" fractions (halves, thirds, quarters, eighths) exact; anything else -- the
    // result of mixing units with decimal conversion factors -- rounds to the nearest 1/8, never
    // below 1/8 so a small positive amount doesn't display as 0.
    fun niceRound(): Rational {
        if (den <= BigInteger.valueOf(4) || den == BigInteger.valueOf(8)) return this
        val eighths = num.multiply(BigInteger.valueOf(16)).add(den).divide(den.multiply(BigInteger.TWO))
        return of(eighths.max(BigInteger.ONE), BigInteger.valueOf(8))
    }

    fun fitsInt(): Boolean = num.bitLength() < 32 && den.bitLength() < 32
}
