package app.tally.logic

import app.tally.data.AppState
import app.tally.data.Category
import app.tally.data.Expense
import app.tally.data.Frequency
import app.tally.data.Recurring
import app.tally.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AskEngineTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-09")
    private var n = 0
    private fun tx(merchant: String, amount: Double, date: String, cat: Category, income: Boolean = false) = Expense(
        id = "t${n++}", amount = amount, merchant = merchant, category = cat,
        timestamp = LocalDate.parse(date).atTime(12, 0).toInstant(utc).toEpochMilli(), source = Source.IMPORT, income = income,
    )
    private val state = AppState(
        currency = "€",
        balance = 3000.0,
        balanceSetAt = 0,
        expenses = listOf(
            tx("REWE", 20.0, "2026-10-02", Category.GROCERIES), tx("REWE", 30.0, "2026-10-05", Category.GROCERIES),
            tx("Uber Eats", 25.0, "2026-10-03", Category.FOOD), tx("Swiggy", 10.0, "2026-09-12", Category.FOOD),
            tx("Uber Eats", 40.0, "2026-09-20", Category.FOOD), tx("Media Markt", 240.0, "2026-09-14", Category.SHOPPING),
            tx("Acme GmbH", 1400.0, "2026-09-29", Category.INCOME, income = true),
        ),
        recurring = listOf(
            Recurring("r", "Rent", 1395.0, false, Category.HOUSING, Frequency.MONTHLY, 30, startEpochDay = 0, postedThroughEpochDay = today.toEpochDay()),
            Recurring("c", "Claude", 21.0, false, Category.SUBSCRIPTIONS, Frequency.MONTHLY, 28, startEpochDay = 0, postedThroughEpochDay = today.toEpochDay()),
        ),
    )
    private fun ask(q: String) = AskEngine.answer(q, state, today, utc)

    @Test fun categoryAndRange() {
        val a = ask("How much did I spend on food last month?")
        assertTrue(a.text, a.text.contains("€50,00") && a.text.contains("September"))
        assertTrue(ask("how much on groceries this month").text.contains("€50,00"))
    }

    @Test fun shopByName() {
        val a = ask("how much at uber eats in september?")
        assertTrue(a.text, a.text.contains("€40,00"))
    }

    @Test fun topPlaces() {
        val a = ask("Where did I spend the most last month?")
        assertTrue(a.text, a.text.indexOf("Media Markt") < a.text.indexOf("Uber Eats"))
    }

    @Test fun income() {
        assertTrue(ask("how much did I earn last month").text.contains("€1.400,00"))
    }

    @Test fun subscriptionsAndRunway() {
        assertTrue(ask("what subscriptions do I pay").text.contains("Claude"))
        assertTrue(ask("when will my money run out?").text.contains("lasts about"))
    }

    @Test fun compareWithLastMonth() {
        // 75 so far this month vs 10 by the same day last month (Swiggy on the 12th is after the 9th).
        val a = ask("compare my spending with last month")
        assertTrue(a.text, a.text.contains("€75,00"))
    }

    @Test fun mayAsVerbIsNotAMonth() {
        assertEquals("this month", AskEngine.range(" how much may i spend ", today).label)
        assertTrue(AskEngine.range(" spent in may ", today).label.contains("May"))
    }

    @Test fun unknownQuestion() {
        assertFalse(ask("tell me a joke").understood)
    }

    @Test fun summaryHasTheBasics() {
        val s = AskEngine.summary(state, today, utc)
        assertTrue(s.contains("Balance") && s.contains("Rent") && s.contains("This month by category"))
    }
}
