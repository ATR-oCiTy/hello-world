package app.tally.logic

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

class LogicTest {

    private val utc = ZoneOffset.UTC
    private fun d(s: String) = LocalDate.parse(s)
    private fun rec(
        amount: Double, day: Int, income: Boolean = false, start: String = "2026-01-01",
        freq: Frequency = Frequency.MONTHLY, month: Int = 1,
    ) = Recurring(
        id = "r$amount$day", name = "x", amount = amount, income = income, category = Category.OTHER,
        frequency = freq, day = day, month = month,
        startEpochDay = d(start).toEpochDay(), postedThroughEpochDay = d(start).toEpochDay() - 1,
    )
    private fun tx(amount: Double, date: String, income: Boolean = false, source: Source = Source.MANUAL) = Expense(
        id = "$amount$date", amount = amount, merchant = "m", category = Category.OTHER,
        timestamp = d(date).atTime(12, 0).toInstant(utc).toEpochMilli(), source = source, income = income,
    )

    // ---- Recurrence ----

    @Test fun monthlyClampsToShortMonths() {
        val r = rec(10.0, 31)
        assertEquals(
            listOf(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30")),
            Recurrence.occurrences(r, d("2026-01-01"), d("2026-04-30")),
        )
    }

    @Test fun yearlyOnlyInItsMonth() {
        val r = rec(120.0, 15, freq = Frequency.YEARLY, month = 3)
        assertEquals(listOf(d("2026-03-15"), d("2027-03-15")), Recurrence.occurrences(r, d("2026-01-01"), d("2027-12-31")))
    }

    @Test fun dueCatchesUpMissedMonthsOnce() {
        val r = rec(58.0, 1, start = "2026-08-01")
        assertEquals(3, Recurrence.due(r, d("2026-10-09")).size)
        val posted = r.copy(postedThroughEpochDay = d("2026-10-09").toEpochDay())
        assertEquals(0, Recurrence.due(posted, d("2026-10-09")).size)
        assertEquals(d("2026-11-01"), Recurrence.nextDue(posted, d("2026-10-09")))
    }

    @Test fun neverBeforeStart() {
        val r = rec(58.0, 1, start = "2026-10-09")
        assertEquals(d("2026-11-01"), Recurrence.nextDue(r, d("2026-10-09")))
    }

    // ---- Metrics ----

    @Test fun dailySpendIgnoresRecurringAndIncome() {
        val today = d("2026-10-30")
        val txs = listOf(
            tx(70.0, "2026-10-01"), tx(70.0, "2026-10-20"),
            tx(500.0, "2026-10-01", source = Source.RECURRING),
            tx(900.0, "2026-10-01", income = true),
        )
        // 140 over 30 days of history.
        assertEquals(140.0 / 30, Metrics.dailyVariableSpend(txs, today, utc), 1e-9)
    }

    @Test fun monthStatsBudgetMath() {
        val today = d("2026-10-10")
        val txs = listOf(
            tx(58.0, "2026-10-01", source = Source.RECURRING),
            tx(100.0, "2026-10-05"),
            tx(50.0, "2026-09-05"),
        )
        val tk = rec(130.0, 15)
        val s = Metrics.month(txs, listOf(tk), budget = 800.0, today = today, zone = utc)
        assertEquals(158.0, s.spent, 1e-9)
        assertEquals(130.0, s.upcomingFixed, 1e-9)
        assertEquals(800.0 - 158.0 - 130.0, s.safeToSpend!!, 1e-9)
        assertEquals(22, s.daysLeft)
        assertEquals(50.0, s.lastMonthToDate, 1e-9)
        assertTrue(s.projectedSpend > s.spent + s.upcomingFixed)
    }

    @Test fun runwayRunsOutOnTheRightDay() {
        val r = Metrics.runway(balance = 100.0, today = d("2026-10-01"), dailySpend = 10.0, recurring = emptyList())
        // Spending starts tomorrow: 100 covers 2–11 Oct, so the 12th is the first day below zero.
        assertEquals(d("2026-10-12"), r.runOut)
    }

    @Test fun runwayCountsSalaryAndRent() {
        val salary = rec(1000.0, 1, income = true, start = "2026-10-02")
        val rent = rec(400.0, 3, start = "2026-10-02")
        // Net +600/month minus 10/day ≈ +295/month: never runs out.
        val ok = Metrics.runway(1000.0, d("2026-10-01"), 10.0, listOf(salary, rent))
        assertNull(ok.runOut)
        assertTrue(ok.monthlyNet > 0)
        // Spending 40/day outpaces it.
        val bad = Metrics.runway(1000.0, d("2026-10-01"), 40.0, listOf(salary, rent))
        assertNotNull(bad.runOut)
        assertTrue(bad.monthlyNet < 0)
    }

    // ---- Statement import ----

    @Test fun germanCsv() {
        val csv = """
            Buchungstag;Valuta;Auftraggeber/Empfänger;Verwendungszweck;Betrag
            01.10.2026;01.10.2026;Techniker Krankenkasse;Beitrag 10/2026;-134,54
            02.10.2026;02.10.2026;ACME GmbH;Gehalt;1.250,00
        """.trimIndent()
        val rows = StatementParser.parse(csv)
        assertEquals(2, rows.size)
        assertEquals(134.54, rows[0].amount, 1e-9)
        assertEquals(false, rows[0].income)
        assertTrue(rows[0].description.contains("Techniker"))
        assertEquals(1250.0, rows[1].amount, 1e-9)
        assertEquals(true, rows[1].income)
    }

    @Test fun pdfTextWithBalanceColumn() {
        val text = """
            Kontoauszug Oktober 2026
            Alter Kontostand 1.500,00
            01.10.2026 01.10.2026 SEPA-Lastschrift Deutschlandticket -58,00 1.442,00
            Mandatsreferenz ABC123
            03.10.2026 03.10.2026 Kartenzahlung REWE Markt -23,45 1.418,55
            05.10.2026 05.10.2026 Gutschrift ACME GmbH Gehalt 1.250,00 2.668,55
            Neuer Kontostand 2.668,55
        """.trimIndent()
        val rows = StatementParser.parse(text)
        assertEquals(3, rows.size)
        assertEquals(58.0, rows[0].amount, 1e-9)
        assertEquals(false, rows[0].income)
        assertEquals(d("2026-10-01"), rows[0].date)
        assertTrue(rows[0].description, rows[0].description.contains("Deutschlandticket"))
        assertEquals(23.45, rows[1].amount, 1e-9)
        assertEquals(true, rows[2].income)
        assertEquals(1250.0, rows[2].amount, 1e-9)
    }

    @Test fun englishStatement() {
        val text = """
            09 Oct 2026  Claude.ai subscription   -€18.00
            10 Oct 2026  Hack The Box   € -14.00
            Closing balance 900.00
        """.trimIndent()
        val rows = StatementParser.parse(text)
        assertEquals(2, rows.size)
        assertEquals(18.0, rows[0].amount, 1e-9)
        assertEquals(d("2026-10-09"), rows[0].date)
        assertEquals(false, rows[1].income)
    }

    @Test fun merchantNamesAreCleaned() {
        assertEquals("Techniker Krankenkasse", StatementParser.merchantFrom("SEPA-Lastschrift Techniker Krankenkasse Mandatsreferenz 123"))
        assertEquals("REWE Markt", StatementParser.merchantFrom("Kartenzahlung REWE Markt"))
        assertEquals("Techniker Krankenkasse", StatementParser.merchantFrom("Techniker Krankenkasse · Beitrag 10/2026"))
        assertEquals("Hack The Box", StatementParser.merchantFrom("Hack The Box"))
    }

    @Test fun billPaidEarlyIsNotCountedTwiceInTheSameMonth() {
        // Rent is due on the 30th but September's went out on 1 October.
        val rent = rec(1395.0, 30).copy(id = "rent", postedThroughEpochDay = d("2026-10-09").toEpochDay())
        val paid = tx(1395.0, "2026-10-01").copy(recurringId = "rent")
        val s = Metrics.month(listOf(paid), listOf(rent), budget = 2200.0, today = d("2026-10-09"), zone = utc)
        assertEquals(0.0, s.upcomingFixed, 1e-9)
        assertEquals(2200.0 - 1395.0, s.safeToSpend!!, 1e-9)
    }
}
