package app.tally.parser

import app.tally.data.Category

data class ParsedPayment(
    val amount: Double,
    val currency: String?,
    val merchant: String,
    val card: String?,
)

/**
 * Turns a Google Wallet tap-to-pay notification into a payment.
 *
 * Google doesn't document the notification layout and it shifts between versions and
 * locales, so this is deliberately loose: find a money amount anywhere, then take the
 * merchant from the title (Wallet's usual layout) or from an "at/to X" phrase.
 */
object WalletParser {

    /** Google Wallet, plus Play services which posted these alerts on older setups. */
    val SOURCE_PACKAGES = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.google.android.gms",
    )

    private const val NUM = """\d{1,3}(?:[,.\s ]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""
    private const val PREFIX_CUR = """US\$|A\$|C\$|NZ\$|S\$|HK\$|Rs\.?|INR|USD|EUR|GBP|AUD|CAD|JPY|₹|\$|€|£|¥|₩|₱|₺"""
    private const val SUFFIX_CUR = """€|zł|kr|Kč|Ft|lei|EUR|USD|GBP|INR|CHF|SEK|NOK|DKK|PLN"""

    private val prefixAmount = Regex("""(?<![A-Za-z])($PREFIX_CUR)\s?($NUM)(?!\d)""")
    private val suffixAmount = Regex("""(?<![\d.,])($NUM)\s?($SUFFIX_CUR)(?![A-Za-z])""")
    private val cardDigits = Regex("""(?:•|\*|·|x|X){2,}\s?(\d{4})""")
    private val atMerchant = Regex(
        """\b(?:at|to|@)\s+(.+?)(?=\s+(?:with|using|on|via|for)\b|[.•·,\n]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val rejectWords = Regex(
        """declined|failed|unsuccessful|refund|reversed|couldn['’]t|could not|not completed|verify|cancel""",
        RegexOption.IGNORE_CASE,
    )

    private val genericTitles = setOf(
        "google wallet", "google pay", "wallet", "payment", "purchase", "transaction",
        "payment successful", "payment complete", "you paid", "paid",
    )

    fun parse(title: String?, text: String?): ParsedPayment? {
        val t = title?.trim().orEmpty()
        val b = text?.trim().orEmpty()
        val all = "$t\n$b"
        if (rejectWords.containsMatchIn(all)) return null

        val (amount, currency) = findAmount(t) ?: findAmount(b) ?: return null
        if (amount <= 0.0) return null

        val titleHasAmount = findAmount(t) != null
        val merchant = when {
            t.isNotEmpty() && !titleHasAmount && t.lowercase() !in genericTitles -> t
            else -> atMerchant.find(all)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Tap & Pay"
        }

        return ParsedPayment(
            amount = amount,
            currency = currency,
            merchant = merchant.take(60),
            card = cardDigits.find(all)?.groupValues?.get(1),
        )
    }

    private fun findAmount(s: String): Pair<Double, String>? {
        if (s.isEmpty()) return null
        prefixAmount.find(s)?.let { m ->
            toNumber(m.groupValues[2])?.let { return it to m.groupValues[1] }
        }
        suffixAmount.find(s)?.let { m ->
            toNumber(m.groupValues[1])?.let { return it to m.groupValues[2] }
        }
        return null
    }

    /** Handles both 1,234.56 and 1.234,56 style grouping. */
    internal fun toNumber(raw: String): Double? {
        val s = raw.replace(" ", "").replace(" ", "")
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val normalized = when {
            lastDot >= 0 && lastComma >= 0 ->
                if (lastDot > lastComma) s.replace(",", "")
                else s.replace(".", "").replace(',', '.')
            lastComma >= 0 ->
                if (s.count { it == ',' } == 1 && s.length - lastComma - 1 <= 2) s.replace(',', '.')
                else s.replace(",", "")
            s.count { it == '.' } > 1 -> s.replace(".", "")
            else -> s
        }
        return normalized.toDoubleOrNull()
    }

    private val categoryKeywords: List<Pair<Category, List<String>>> = listOf(
        Category.SUBSCRIPTIONS to listOf(
            "claude", "anthropic", "hack the box", "hackthebox", "chatgpt", "openai", "netflix",
            "spotify", "youtube", "disney", "prime video", "apple.com", "icloud", "google one",
            "adobe", "github", "notion", "dazn", "audible", "patreon", "subscription", "abo ",
        ),
        Category.INCOME to listOf("gehalt", "salary", "lohn", "payroll", "stipend"),
        Category.FOOD to listOf(
            "cafe", "café", "coffee", "starbucks", "costa", "pret", "mcdonald", "kfc", "burger",
            "pizza", "domino", "subway", "restaurant", " bar ", " pub ", "kitchen", "grill", "bistro",
            "swiggy", "zomato", "eats", "dallmayr", "betriebsrestaurant", "betriebsverpflegun", "kiosk", "kantine", "mensa", "burger", "kfc", "samurai", "lieferando", "wolt", "döner", "backerei", "bäckerei", "deliveroo", "uber eats", "doordash", "bakery", "chai", "dosa", "taco",
        ),
        Category.GROCERIES to listOf(
            "tesco", "sainsbury", "asda", "aldi", "lidl", "waitrose", "walmart", "costco", "kroger",
            "whole foods", "trader joe", "rewe", "edeka", "lebensmittel", "desi", "asia market", "netto", "penny", "kaufland", "dm-drogerie", "rossmann", "dmart", "bigbasket", "blinkit", "zepto", "instamart",
            "reliance fresh", " more ", "spar", "co-op", "market", "grocer", "supermarket",
        ),
        Category.TRANSPORT to listOf(
            "deutschlandticket", "d-ticket", "mvg", "verkehrs", "db vertrieb", "bahn", " db ", "bvg", "mvg", "hvv", "vbb", "rmv", "kvb", "flixbus", "uber", " ola ", "lyft", "bolt", "rapido", "metro", "rail", "train", "tfl", "transit",
            " bus ", "taxi", "cab", "fuel", "petrol", "shell", " bp ", "hpcl", "iocl", "indian oil",
            "parking", "toll", "airline", "airways",
        ),
        Category.SHOPPING to listOf(
            "amazon", "flipkart", "myntra", "zara", "h&m", "uniqlo", "nike", "adidas", "ikea",
            "apple", "store", "mall", "boutique", "decathlon", "primark", "target", "best buy",
        ),
        Category.FUN to listOf(
            "cinema", "kino", "filmpalast", "pvr", "inox", "steam", "playstation", "xbox",
            "bookmyshow", "ticket", "club", "bowling", "arcade", "theatre", "theater", "concert",
        ),
        Category.BILLS to listOf(
            "techniker", " tk ", "lebara", "hochschule", "hdbw", "miele", "reinigung", "aok", "barmer", "versicherung", "rundfunk", "telekom", " o2 ", "stadtwerke", "electric", "power", "water", "gas", "airtel", "jio", "vodafone", " vi ", "broadband",
            "internet", "insurance", " rent ", "bill", "recharge", "mobile",
        ),
        Category.HEALTH to listOf(
            "apotheke", "arzt", "pharma", "chemist", "apollo", "boots", "cvs", "walgreens", "clinic", "hospital",
            "dental", "medical", "gym", "fitness", " cult", "medplus", "1mg",
        ),
    )

    fun guessCategory(merchant: String): Category {
        val m = " ${merchant.lowercase()} "
        return categoryKeywords.firstOrNull { (_, words) -> words.any { m.contains(it) } }?.first
            ?: Category.OTHER
    }
}
