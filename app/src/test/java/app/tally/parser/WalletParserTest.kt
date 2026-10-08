package app.tally.parser

import app.tally.data.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletParserTest {

    private fun parse(title: String?, text: String?) = WalletParser.parse(title, text)

    @Test fun merchantInTitleDollar() {
        val p = parse("Starbucks", "$4.50 with Visa •••• 1234")!!
        assertEquals(4.50, p.amount, 0.001)
        assertEquals("Starbucks", p.merchant)
        assertEquals("1234", p.card)
    }

    @Test fun rupeesWithGrouping() {
        val p = parse("Reliance Smart", "₹1,250.00 · Visa ••5678")!!
        assertEquals(1250.0, p.amount, 0.001)
        assertEquals("Reliance Smart", p.merchant)
        assertEquals("5678", p.card)
    }

    @Test fun rsPrefix() {
        assertEquals(2499.0, parse("Croma", "Rs. 2,499 paid with Mastercard")!!.amount, 0.001)
    }

    @Test fun pounds() {
        assertEquals(12.30, parse("Tesco Express", "£12.30 with Mastercard ••9012")!!.amount, 0.001)
    }

    @Test fun euroCommaDecimal() {
        assertEquals(12.50, parse("Lidl", "€12,50 mit Visa")!!.amount, 0.001)
        assertEquals(12.50, parse("Lidl", "12,50 € mit Visa")!!.amount, 0.001)
        assertEquals(1234.56, parse("Mediamarkt", "1.234,56 €")!!.amount, 0.001)
    }

    @Test fun amountInTitleMerchantFromText() {
        val p = parse("₹250.00 paid to Swiggy", "Paid with Visa ••1111")!!
        assertEquals(250.0, p.amount, 0.001)
        assertEquals("Swiggy", p.merchant)
    }

    @Test fun genericTitleUsesAtPhrase() {
        val p = parse("Google Wallet", "You paid $8.00 at Uber with Visa")!!
        assertEquals(8.0, p.amount, 0.001)
        assertEquals("Uber", p.merchant)
    }

    @Test fun fallbackMerchant() {
        assertEquals("Tap & Pay", parse("Google Pay", "$3.00")!!.merchant)
    }

    @Test fun ignoresNonPayments() {
        assertNull(parse("Google Wallet", "Your card is ready to use"))
        assertNull(parse("Starbucks", "Payment declined · $4.50"))
        assertNull(parse("Amazon", "Refund of $20.00 processed"))
        assertNull(parse(null, null))
    }

    @Test fun cardDigitsAreNotTheAmount() {
        assertEquals(9.99, parse("Spotify", "Mastercard ••4242 · $9.99")!!.amount, 0.001)
    }

    @Test fun categories() {
        assertEquals(Category.FOOD, WalletParser.guessCategory("Starbucks Coffee"))
        assertEquals(Category.GROCERIES, WalletParser.guessCategory("TESCO EXPRESS"))
        assertEquals(Category.TRANSPORT, WalletParser.guessCategory("Uber Trip"))
        assertEquals(Category.FOOD, WalletParser.guessCategory("Uber Eats"))
        assertEquals(Category.OTHER, WalletParser.guessCategory("Business Centre"))
    }
}
