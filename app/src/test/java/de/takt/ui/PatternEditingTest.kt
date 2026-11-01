package de.takt.ui

import org.junit.Assert.*
import org.junit.Test

class PatternEditingTest {
    @Test fun shortBarsPairButLongBarsGetTheirOwnRow() {
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3), listOf(4, 5)),
            patternRows(listOf(4, 4, 7, 9, 3, 4), 352f, 36f))
        assertEquals(listOf(listOf(0), listOf(1)), patternRows(listOf(4, 4), 240f, 36f))
    }
    @Test fun dragAccumulatesEveryMoveWithoutUiFeedback() {
        val drag = TempoDrag(120)
        val updates = List(20) { drag.move(-8f) }
        assertEquals(140, updates.last())
        assertEquals(120, drag.move(160f))
    }
    @Test fun fractionalMovesAndClampsDoNotLoseOrBankSteps() {
        val drag = TempoDrag(998)
        assertNull(drag.move(-4f))
        assertEquals(999, drag.move(-4f))
        assertEquals(999, drag.move(-80f))
        assertEquals(998, drag.move(8f))
        assertEquals(1, drag.move(8000f))
    }
}
