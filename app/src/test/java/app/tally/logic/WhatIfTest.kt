package app.tally.logic

import app.tally.data.AppState
import app.tally.data.Category
import app.tally.data.Frequency
import app.tally.data.Recurring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WhatIfTest {

    private val today = LocalDate.parse("2026-10-09")
    private val salary = Recurring(
        "s", "Infineon", 1411.93, true, Category.INCOME, Frequency.MONTHLY, 29,
        startEpochDay = 0, postedThroughEpochDay = today.toEpochDay(),
    )
    private val rent = Recurring(
        "r", "Rent", 1395.0, false, Category.HOUSING, Frequency.MONTHLY, 30,
        startEpochDay = 0, postedThroughEpochDay = today.toEpochDay(),
    )
    private val state = AppState(balance = 6848.46, balanceSetAt = 0, recurring = listOf(salary, rent), currency = "€")

    private val question = "Okay so, I'm currently getting a salary of 1600 per month pre tax which gives me 1411 post tax. " +
        "From the mid of November my salary shifts to 3000*20/35 pre tax as a student. How does this change my headroom"

    @Test fun arithmetic() {
        assertEquals(1714.2857, WhatIf.evaluate("3000*20/35")!!, 1e-3)
        assertEquals(1800.0, WhatIf.evaluate("1600 + 200")!!, 1e-9)
        assertEquals(1600.5, WhatIf.evaluate("1.600,50")!!, 1e-9)
    }

    @Test fun recognisesTheQuestion() {
        assertTrue(WhatIf.isWhatIf(question))
        assertFalse(WhatIf.isWhatIf("how much did I earn last month"))
    }

    @Test fun salaryRaiseFromMidNovember() {
        val a = AskEngine.answer(question, state, today, ZoneOffset.UTC)
        val c = assertNotNull(a.change).let { a.change!! }
        assertEquals("s", c.planId)
        assertEquals(LocalDate.parse("2026-11-15").toEpochDay(), c.fromEpochDay)
        // 1411 + (1714.29 - 1600) * 0.707 ≈ 1491.8
        assertEquals(1491.8, c.amount, 0.5)
        assertTrue(a.text, a.text.contains("Runway now") && a.text.contains("With the change"))
    }

    @Test fun netAmountGivenDirectly() {
        val a = AskEngine.answer("what if my salary goes up to 1550 net from 1 december", state, today, ZoneOffset.UTC)
        assertEquals(1550.0, a.change!!.amount, 1e-9)
        assertEquals(LocalDate.parse("2026-12-01").toEpochDay(), a.change!!.fromEpochDay)
    }

    @Test fun greeting() {
        assertTrue(AskEngine.answer("Hi", state, today, ZoneOffset.UTC).text.startsWith("Hi!"))
    }
}
