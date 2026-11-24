package dev.prayush.obol.domain.model

import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Paise(val value: Long) : Comparable<Paise> {
    init {
        require(value >= 0L) { "Paise cannot be negative: $value" }
    }

    val rupees: Long
        get() = value / 100L

    val wholeRupeePaise: Paise
        get() = Paise((value / 100L) * 100L)

    val isZero: Boolean
        get() = value == 0L

    val isPositive: Boolean
        get() = value > 0L

    operator fun plus(other: Paise): Paise = Paise(Math.addExact(value, other.value))

    operator fun minus(other: Paise): Paise {
        val diff = Math.subtractExact(value, other.value)
        require(diff >= 0L) { "Paise subtraction underflow: $value - ${other.value}" }
        return Paise(diff)
    }

    fun minusAtLeastZero(other: Paise): Paise =
        Paise(max(0L, value - other.value))

    override fun compareTo(other: Paise): Int = value.compareTo(other.value)

    fun formatRupeesNumber(): String {
        val s = rupees.toString()
        if (s.length <= 3) return s
        val lastThree = s.takeLast(3)
        val prefix = s.dropLast(3)
        val groups = mutableListOf<String>()
        var idx = prefix.length
        while (idx > 0) {
            val start = max(0, idx - 2)
            groups.add(prefix.substring(start, idx))
            idx = start
        }
        groups.reverse()
        return buildString {
            groups.forEach {
                append(it)
                append(',')
            }
            append(lastThree)
        }
    }

    fun formatRupees(): String = "₹${formatRupeesNumber()}"

    companion object {
        val ZERO = Paise(0L)

        fun fromRupees(rupees: Long): Paise {
            require(rupees >= 0L) { "Rupees cannot be negative: $rupees" }
            return Paise(Math.multiplyExact(rupees, 100L))
        }

        fun minOf(a: Paise, b: Paise): Paise = Paise(min(a.value, b.value))
        fun maxOf(a: Paise, b: Paise): Paise = Paise(max(a.value, b.value))
    }
}

fun Iterable<Paise>.sumPaise(): Paise {
    var acc = Paise.ZERO
    for (item in this) {
        acc += item
    }
    return acc
}
