package app.tally.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Synthetic rows laid out like a UniCredit / Aion statement page (column x positions as in the real PDF). */
class UniCreditStatementTest {

    private val words = mutableListOf<Word>()
    private fun at(x: Float, y: Float, text: String, page: Int = 1) {
        // Split like the PDF library would: one Word per space-separated token, advancing x.
        var cx = x
        text.split(' ').forEach { t -> words += Word(page, cx, y, t); cx += t.length * 3.5f + 2f }
    }

    private fun header() {
        at(40f, 523f, "Transaction date"); at(134.2f, 523f, "Description"); at(272.6f, 523f, "Operation title")
        at(357.7f, 523f, "Operation type"); at(475.8f, 523f, "Amount"); at(541.6f, 523f, "Balance")
        at(40f, 536f, "Booking date"); at(134.2f, 536f, "Participant"); at(205.7f, 536f, "Card No.")
        at(357.7f, 536f, "Operation No.")
    }

    private fun build(): List<StatementLine> {
        header()
        // Foreign-currency card payment: INR first, EUR below, then a rate line.
        at(40f, 554f, "01.01.2026"); at(134.2f, 554f, "SOME CAFE"); at(205.7f, 554f, "*1234")
        at(272.6f, 554f, "SOME CAFE"); at(357.7f, 554f, "Card transactions"); at(465.7f, 554f, "-4 284.00 INR")
        at(533.4f, 554f, "4 390.28 EUR")
        at(40f, 561f, "01.01.2026"); at(134.2f, 561f, "BANGALORE, IN"); at(357.7f, 561f, "cb47e0ff-8ca2-4eb3-")
        at(474.3f, 561f, "-41.43 EUR")
        at(451.1f, 574f, "1 INR=0.00967 EUR")
        // Direct debit: IBAN wrapped over two lines, then the payee name, then the footer.
        at(40f, 591f, "02.01.2026"); at(134.2f, 591f, "DE1820010020003093"); at(205.7f, 591f, "---")
        at(272.6f, 591f, "TK-BuchNr 0000 Monat 12/25"); at(357.7f, 591f, "Direct debit"); at(473.2f, 591f, "-144.24 EUR")
        at(534f, 591f, "4 246.04 EUR")
        at(40f, 598f, "02.01.2026"); at(134.2f, 598f, "6201"); at(272.6f, 598f, "Beitraege"); at(357.7f, 598f, "486a90bd-b129-48ca-")
        at(134.2f, 605f, "Techniker"); at(357.7f, 605f, "b631-cf0b6e4e99d1")
        at(134.2f, 612f, "Krankenkasse")
        at(250f, 900f, "Issued on 09.10.2026 08:57:47")
        // Page 2: salary from an employer with a street address, and a card refund.
        at(40f, 50f, "29.01.2026", 2); at(134.2f, 50f, "DE6770070010020438", 2); at(205.7f, 50f, "---", 2)
        at(272.6f, 50f, "Lohn/Gehalt 30223188", 2); at(357.7f, 50f, "Incoming transfer", 2)
        at(470f, 50f, "1 313.42 EUR", 2); at(533f, 50f, "5 559.46 EUR", 2)
        at(40f, 57f, "29.01.2026", 2); at(134.2f, 57f, "Acme Widgets AG Am", 2)
        at(134.2f, 64f, "Campeon 1-15", 2)
        at(40f, 80f, "30.01.2026", 2); at(134.2f, 80f, "NYX*CoffeeAutomat", 2); at(205.7f, 80f, "*1234", 2)
        at(272.6f, 80f, "NYX*CoffeeAutomat", 2); at(357.7f, 80f, "Card transactions", 2)
        at(480f, 80f, "1.70 EUR", 2); at(533f, 80f, "5 561.16 EUR", 2)
        at(40f, 87f, "30.01.2026", 2); at(134.2f, 87f, "Muenchen, DE", 2)
        return UniCreditStatement.parse(words)!!
    }

    @Test fun readsEveryRowWithEurAmounts() {
        val rows = build()
        assertEquals(4, rows.size)

        val cafe = rows[0]
        assertEquals(LocalDate.of(2026, 1, 1), cafe.date)
        assertEquals(41.43, cafe.amount, 1e-9)
        assertEquals(false, cafe.income)
        assertEquals("Some Cafe", cafe.merchant)
        assertEquals("1234", cafe.card)
        assertEquals(4390.28, cafe.balanceAfter!!, 1e-9)

        val tk = rows[1]
        assertEquals(144.24, tk.amount, 1e-9)
        assertEquals("Techniker Krankenkasse", tk.merchant)
        assertTrue(tk.note, tk.note.startsWith("TK-BuchNr"))
        assertEquals("Direct debit", tk.kind)
        // The footer must not leak into the description.
        assertTrue(!tk.description.contains("Issued"))

        val salary = rows[2]
        assertEquals(true, salary.income)
        assertEquals(1313.42, salary.amount, 1e-9)
        assertEquals("Acme Widgets AG", salary.merchant)
        assertEquals("Incoming transfer", salary.kind)

        val refund = rows[3]
        assertEquals(true, refund.income)
        assertEquals("CoffeeAutomat", refund.merchant)
    }

    @Test fun notAUniCreditStatement() {
        assertNull(UniCreditStatement.parse(listOf(Word(1, 10f, 10f, "Hello"))))
    }

    @Test fun payeeNames() {
        assertEquals("Lebara Germany Limited", UniCreditStatement.payeeName(listOf("DE123456789012345678", "Lebara Germany", "Limited Copthall", "Avenue E")))
        assertEquals("DB Vertrieb GmbH", UniCreditStatement.payeeName(listOf("DE1234567890123456", "DB Vertrieb GmbH", "Europa-Allee 70-76, Fra")))
        assertEquals("Jane Doe", UniCreditStatement.payeeName(listOf("DE1234567890123456", "Jane Doe Carl-Wery-Str", "CO - REV")))
        assertEquals("Hochschule der Bayerischen Wirtschaft", UniCreditStatement.payeeName(listOf("DE12345678901234", "Hochschule der", "Bayerischen", "Wirtschaft (H")))
    }

    @Test fun prettify() {
        assertEquals("AloisDallmayrAutom", UniCreditStatement.prettify("NYX*AloisDallmayrAutom"))
        assertEquals("Uber Eats", UniCreditStatement.prettify("UBER * EATS PENDING"))
        assertEquals("Rossmann", UniCreditStatement.prettify("Rossmann 3572"))
        assertEquals("Aldi Sued", UniCreditStatement.prettify("ALDI SUED"))
        assertEquals("Express Kiosk", UniCreditStatement.prettify("SumUp *Express Kiosk/"))
        assertEquals("Google Play App", UniCreditStatement.prettify("GOOGLE*GOOGLE PLAY APP"))
    }

    @Test fun wordsSplitOnGaps() {
        val g = { x: Float, t: String -> Glyph(1, x, 10f, 3f, 2f, t) }
        // "AB" touching, then a 6pt gap, then "C" — and a different line.
        val w = Words.fromGlyphs(listOf(g(0f, "A"), g(3f, "B"), g(12f, "C"), Glyph(1, 0f, 20f, 3f, 2f, "D")))
        assertEquals(listOf("AB", "C", "D"), w.map { it.text })
    }
}
