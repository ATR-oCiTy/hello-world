package app.tally.logic

import app.tally.data.AmountChange
import app.tally.data.Category
import app.tally.data.Expense
import app.tally.data.Frequency
import app.tally.data.Recurring
import app.tally.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class RecurringDetectorTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-09")
    private var n = 0
    private fun tx(merchant: String, amount: Double, date: String, income: Boolean = false, cat: Category = Category.OTHER) = Expense(
        id = "t${n++}", amount = amount, merchant = merchant, category = cat,
        timestamp = LocalDate.parse(date).atTime(12, 0).toInstant(utc).toEpochMilli(), source = Source.IMPORT, income = income,
    )
    private fun detect(txs: List<Expense>, plans: List<Recurring> = emptyList(), dismissed: Set<String> = emptySet()) =
        RecurringDetector.detect(txs, plans, dismissed, today, utc)

    private val salary = listOf("2026-06-29", "2026-07-30", "2026-08-28", "2026-09-29").mapIndexed { i, d ->
        tx("Acme GmbH", if (i < 2) 1313.42 else 1411.93, d, income = true)
    }

    @Test fun findsSalaryWithLatestAmount() {
        val s = detect(salary).single()
        assertEquals(true, s.income)
        assertEquals(1411.93, s.amount, 1e-9)
        assertEquals(Category.INCOME, s.category)
        assertTrue(s.day in 28..30)
        assertEquals(LocalDate.parse("2026-09-29"), s.lastDate)
    }

    @Test fun rentAcrossTwoPayeesAndSpellings() {
        val rent = listOf(
            tx("REVO Muenchen GmbH", 1350.0, "2026-05-29"), tx("REVO Munchen GmbH", 1395.0, "2026-06-30"),
            tx("Gibpenhw", 1395.0, "2026-07-30"), tx("REVO Munchen GmbH", 1350.0, "2026-08-29"),
            tx("REVO Munchen GmbH", 1395.0, "2026-10-01"),
            // Small purchases at the same place mustn't break it.
            tx("REVO Munchen GmbH", 7.5, "2026-05-20"),
        )
        val s = detect(rent).single { it.amount > 1000 }
        assertEquals("Rent", s.name)
        assertEquals(Category.HOUSING, s.category)
        assertTrue(s.payees.size >= 2)
    }

    @Test fun ignoresShopsAndStaleSeries() {
        val shop = (1..9).flatMap { m -> listOf(5, 12, 19).map { d -> tx("REWE", 20.0 + d, "2026-0$m-%02d".format(d)) } }
        val ended = listOf("2026-05-24", "2026-06-24", "2026-07-24", "2026-08-24").map { tx("Expatrio M", 992.0, it, income = true) }
        assertTrue(detect(shop + ended).isEmpty())
    }

    @Test fun skipsWhatIsAlreadyPlannedOrDismissed() {
        val plan = Recurring(
            id = "p", name = "Salary", amount = 1400.0, income = true, category = Category.INCOME,
            frequency = Frequency.MONTHLY, day = 29, startEpochDay = 0, postedThroughEpochDay = 0,
        )
        assertTrue(detect(salary, plans = listOf(plan)).isEmpty())
        val id = detect(salary).single().id
        assertTrue(detect(salary, dismissed = setOf(id)).isEmpty())
    }

    @Test fun raisesAndEndDatesDriveTheForecast() {
        val start = LocalDate.parse("2026-10-01")
        val pay = Recurring(
            id = "s", name = "Salary", amount = 1000.0, income = true, category = Category.INCOME,
            frequency = Frequency.MONTHLY, day = 29, startEpochDay = start.toEpochDay(),
            postedThroughEpochDay = start.toEpochDay() - 1,
            changes = listOf(AmountChange(LocalDate.parse("2026-11-15").toEpochDay(), 1500.0)),
        )
        assertEquals(1000.0, pay.amountOn(LocalDate.parse("2026-10-29")), 1e-9)
        assertEquals(1500.0, pay.amountOn(LocalDate.parse("2026-11-29")), 1e-9)

        val ends = pay.copy(endEpochDay = LocalDate.parse("2026-12-31").toEpochDay())
        assertEquals(3, Recurrence.occurrences(ends, start, LocalDate.parse("2027-06-30")).size)
        assertNull(Recurrence.nextDue(ends, LocalDate.parse("2027-01-01")))

        // 40/day (~1217/month) vs a 1000 → 1500 salary: runs out without the raise, lasts with it.
        val without = Metrics.runway(2000.0, start, 40.0, listOf(pay.copy(changes = emptyList())))
        val with = Metrics.runway(2000.0, start, 40.0, listOf(pay))
        assertNotNull(without.runOut)
        assertNull(with.runOut)
    }
}
