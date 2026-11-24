package dev.prayush.obol.domain

import dev.prayush.obol.domain.model.Paise
import dev.prayush.obol.domain.model.sumPaise
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaiseTest {

    @Test
    fun `rupee floor rounding truncates sub-rupee paise`() {
        assertEquals(0L, Paise.ZERO.rupees)
        assertEquals(0L, Paise(99L).rupees)
        assertEquals(1L, Paise(100L).rupees)
        assertEquals(1L, Paise(199L).rupees)
        assertEquals(Paise(10_000L), Paise(10_099L).wholeRupeePaise)
        assertTrue(Paise.ZERO.isZero)
        assertFalse(Paise.ZERO.isPositive)
        assertTrue(Paise(1L).isPositive)
    }

    @Test
    fun `indian rupee formatting handles hundreds thousands lakhs and crores`() {
        assertEquals("₹0", Paise.ZERO.formatRupees())
        assertEquals("₹99", Paise.fromRupees(99L).formatRupees())
        assertEquals("₹100", Paise.fromRupees(100L).formatRupees())
        assertEquals("₹999", Paise.fromRupees(999L).formatRupees())
        assertEquals("₹1,000", Paise.fromRupees(1_000L).formatRupees())
        assertEquals("₹3,000", Paise.fromRupees(3_000L).formatRupees())
        assertEquals("₹99,999", Paise.fromRupees(99_999L).formatRupees())
        assertEquals("₹1,00,000", Paise.fromRupees(100_000L).formatRupees())
        assertEquals("₹1,24,000", Paise.fromRupees(124_000L).formatRupees())
        assertEquals("₹1,23,45,678", Paise.fromRupees(12_345_678L).formatRupees())
    }

    @Test
    fun `non-negative and overflow invariants hold`() {
        assertThrows(IllegalArgumentException::class.java) {
            Paise(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Paise.fromRupees(-5L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Paise(100L) - Paise(200L)
        }
        assertEquals(Paise.ZERO, Paise(100L).minusAtLeastZero(Paise(200L)))
        assertEquals(Paise(300L), listOf(Paise(100L), Paise(200L)).sumPaise())

        assertThrows(ArithmeticException::class.java) {
            Paise(Long.MAX_VALUE) + Paise(1L)
        }
        assertThrows(ArithmeticException::class.java) {
            Paise.fromRupees(Long.MAX_VALUE)
        }
    }

    @Test
    fun `json serialization round-trips value class`() {
        val original = Paise(12_400L)
        val encoded = Json.encodeToString(original)
        assertEquals(original, Json.decodeFromString<Paise>(encoded))
    }
}
