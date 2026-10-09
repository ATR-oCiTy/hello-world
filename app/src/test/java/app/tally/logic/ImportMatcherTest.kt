package app.tally.logic

import app.tally.data.Category
import app.tally.data.Expense
import app.tally.data.Frequency
import app.tally.data.Recurring
import app.tally.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ImportMatcherTest {

    private val utc = ZoneOffset.UTC
    private fun line(date: String, amount: Double, merchant: String, id: String? = null, income: Boolean = false) =
        StatementLine(LocalDate.parse(date), amount, income, merchant, merchant = merchant, externalId = id)
    private fun tx(id: String, date: String, amount: Double, merchant: String, source: Source, extId: String? = null, plan: String? = null) = Expense(
        id = id, amount = amount, merchant = merchant, category = Category.OTHER,
        timestamp = LocalDate.parse(date).atTime(12, 0).toInstant(utc).toEpochMilli(), source = source,
        externalId = extId, recurringId = plan,
    )
    private fun match(lines: List<StatementLine>, txs: List<Expense>, plans: List<Recurring> = emptyList(), skipped: Set<String> = emptySet()) =
        ImportMatcher.match(lines, txs, plans, skipped, utc)

    @Test fun overlappingStatementMatchesByBankId() {
        val existing = listOf(
            tx("a", "2026-10-03", 2.40, "Yormas", Source.IMPORT, extId = "id-1"),
            tx("b", "2026-10-03", 2.40, "Yormas", Source.IMPORT, extId = "id-2"),
        )
        // Same day, same amount, three coffees: two are known, the third is new.
        val m = match(
            listOf(line("2026-10-03", 2.40, "Yormas", "id-1"), line("2026-10-03", 2.40, "Yormas", "id-2"), line("2026-10-03", 2.40, "Yormas", "id-3")),
            existing,
        )
        assertEquals(MatchKind.DUPLICATE, m[0]!!.kind)
        assertEquals(MatchKind.DUPLICATE, m[1]!!.kind)
        assertNull(m[2])
    }

    @Test fun olderImportsWithoutIdsMatchOnDayAndAmount() {
        val m = match(listOf(line("2026-10-03", 21.75, "REWE", "id-9")), listOf(tx("a", "2026-10-03", 21.75, "REWE", Source.IMPORT)))
        assertEquals(MatchKind.DUPLICATE, m[0]!!.kind)
    }

    @Test fun walletTapSettlesAFewDaysLater() {
        val m = match(listOf(line("2026-10-06", 4.50, "Starbucks", "x")), listOf(tx("w", "2026-10-04", 4.50, "Starbucks", Source.WALLET)))
        assertEquals(MatchKind.DUPLICATE, m[0]!!.kind)
    }

    @Test fun realRentReplacesThePlanEstimate() {
        val plan = Recurring(
            id = "rent", name = "Rent", amount = 1395.0, income = false, category = Category.HOUSING,
            frequency = Frequency.MONTHLY, day = 30, startEpochDay = 0, postedThroughEpochDay = 0, matchKey = "gibpenhw|revo munchen gmbh",
        )
        val estimate = tx("e", "2026-10-30", 1395.0, "Rent", Source.RECURRING, plan = "rent")
        // Paid two days later and a different amount: still the same rent.
        val m = match(listOf(line("2026-11-01", 1350.0, "REVO Muenchen GmbH", "r1")), listOf(estimate), listOf(plan))
        assertEquals(MatchKind.REPLACES_PLANNED, m[0]!!.kind)
        assertEquals("e", m[0]!!.existing!!.id)
    }

    @Test fun rowsSkippedLastTimeStaySkipped() {
        val l = line("2026-10-12", 150.0, "Friend", "f1")
        assertEquals(MatchKind.SKIPPED_BEFORE, match(listOf(l), emptyList(), skipped = setOf(ImportMatcher.fingerprint(l)))[0]!!.kind)
    }
}
