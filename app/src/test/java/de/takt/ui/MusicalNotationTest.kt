package de.takt.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicalNotationTest {
    @Test fun referenceValuesHaveCorrectHeadsFlagsAndDots() {
        listOf(4.0, 2.0, 1.0, .5, .25, .125).forEachIndexed { i, value ->
            assertEquals(NoteShape(1 shl i, false), noteShape(value))
            assertEquals(NoteShape(1 shl i, true), noteShape(value * 1.5))
        }
        assertEquals(1, noteShape(.5).flags)
        assertEquals(2, noteShape(.25).flags)
        assertEquals(3, noteShape(.125).flags)
    }
}
