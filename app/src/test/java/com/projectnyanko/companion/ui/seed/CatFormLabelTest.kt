package com.projectnyanko.companion.ui.seed

import org.junit.Assert.assertEquals
import org.junit.Test

/** The four evolution stages have fixed names; a fifth would fall back to a number. */
class CatFormLabelTest {
    @Test
    fun namesTheFourStages() {
        assertEquals("Base Form", formLabel(0))
        assertEquals("Evolved Form", formLabel(1))
        assertEquals("True Form", formLabel(2))
        assertEquals("Ultra Form", formLabel(3))
    }

    @Test
    fun fallsBackToANumberPastUltra() {
        assertEquals("Form 5", formLabel(4))
    }
}
