package app.tally.logic

import app.tally.parser.WalletParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class StatementLine(
    val date: LocalDate,
    val amount: Double,
    val income: Boolean,
    val description: String,
)

/**
 * Pulls transactions out of a bank statement: a CSV export, or the text of a PDF statement.
 *
 * Expatrio's exact layout isn't documented, so this reads any row that has a date and a
 * two-decimal amount, in German (1.234,56) or English (1,234.56) style. Everything goes through
 * a preview where you can untick or flip rows before importing.
 */
object StatementParser {

    private val dmy = Regex("""\b(\d{1,2})[./](\d{1,2})[./](\d{2,4})\b""")
    private val iso = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val named = Regex(
        """\b(\d{1,2})\.?\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Mär|Mai|Okt|Dez)[a-zä]*\.?\s+(\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val amount = Regex(
        """(?<![\d.,])([-+−]\s?)?(?:€\s?|EUR\s?)?(\d{1,3}(?:[.,\s ]\d{3})*[.,]\d{2}|\d+[.,]\d{2})(?![\d]|[.,]\d)(\s?(?:€|EUR))?(\s?[-+SH](?![A-Za-z]))?""",
    )

    private val skipLine = Regex(
        """saldo|kontostand|balance|übertrag|opening|closing|summe|total|zwischensumme|seite|page\s+\d""",
        RegexOption.IGNORE_CASE,
    )
    private val incomeWords = Regex(
        """gutschrift|gehalt|lohn|salary|wage|eingang|incoming|credit\b|received|erstattung|refund|zahlungseingang""",
        RegexOption.IGNORE_CASE,
    )

    private val boilerplate = Regex(
        """^(?:sepa[- ]?)?(?:lastschrift|basislastschrift|kartenzahlung|kartenumsatz|gutschrift|überweisung|ueberweisung|dauerauftrag|card payment|direct debit|transfer|payment|zahlung)\b[\s:/-]*""",
        RegexOption.IGNORE_CASE,
    )
    private val referenceTail = Regex(
        """\b(?:mandat\w*|mandatsreferenz|gläubiger[- ]?id|glaeubiger|end[- ]?to[- ]?end|eref|mref|cred|ref(?:erence)?|iban|bic)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    /** "SEPA-Lastschrift Techniker Krankenkasse Mandatsreferenz 123" → "Techniker Krankenkasse". */
    fun merchantFrom(description: String): String {
        var s = description.substringBefore(" · ").trim()
        repeat(2) { s = s.replace(boilerplate, "").trim() }
        s = s.replace(referenceTail, "").trim(' ', ',', '/', '-', ':')
        return s.ifEmpty { description }.take(40)
    }

    fun parse(text: String): List<StatementLine> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        return parseCsv(lines) ?: parseText(lines)
    }

    // ---------- CSV ----------

    private val dateHeaders = listOf("buchungstag", "buchungsdatum", "datum", "date", "booking date", "valuta", "wertstellung", "value date")
    private val amountHeaders = listOf("betrag", "amount", "umsatz", "value", "summe")
    private val descHeaders = listOf(
        "verwendungszweck", "beschreibung", "description", "empfänger", "zahlungsempfänger", "auftraggeber",
        "name", "payee", "counterparty", "partner", "reference", "buchungstext", "details",
    )

    private fun parseCsv(lines: List<String>): List<StatementLine>? {
        val sep = listOf(';', ',', '\t').maxBy { c -> lines.take(5).sumOf { l -> l.count { it == c } } }
        val headerIndex = lines.take(15).indexOfFirst { l ->
            val cells = splitCsv(l, sep).map { it.lowercase() }
            cells.any { c -> dateHeaders.any { c.contains(it) } } && cells.any { c -> amountHeaders.any { c.contains(it) } }
        }
        if (headerIndex < 0) return null
        val header = splitCsv(lines[headerIndex], sep).map { it.lowercase() }
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOfFirst { it.contains(n) }.takeIf { it >= 0 } }
        val dateCol = col(dateHeaders) ?: return null
        val amountCol = col(amountHeaders) ?: return null
        val descCols = header.indices.filter { i -> descHeaders.any { header[i].contains(it) } }
        val creditCol = header.indexOfFirst { it.contains("soll/haben") || it == "s/h" || it.contains("debit/credit") }

        return lines.drop(headerIndex + 1).mapNotNull { line ->
            val cells = splitCsv(line, sep)
            val date = cells.getOrNull(dateCol)?.let(::parseDate) ?: return@mapNotNull null
            val raw = cells.getOrNull(amountCol)?.trim() ?: return@mapNotNull null
            val negative = raw.startsWith("-") || raw.startsWith("−") || raw.endsWith("-") ||
                (creditCol >= 0 && cells.getOrNull(creditCol)?.trim()?.uppercase() == "S")
            val value = WalletParser.toNumber(raw.filter { it.isDigit() || it == ',' || it == '.' }) ?: return@mapNotNull null
            if (value == 0.0) return@mapNotNull null
            val desc = descCols.mapNotNull { cells.getOrNull(it)?.trim()?.takeIf(String::isNotEmpty) }
                .distinct().joinToString(" · ").ifEmpty { "Bank transaction" }
            StatementLine(date, value, income = !negative, description = desc.take(120))
        }
    }

    private fun splitCsv(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        line.forEach { c ->
            when {
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    // ---------- PDF text ----------

    private fun parseText(lines: List<String>): List<StatementLine> {
        val out = mutableListOf<StatementLine>()
        var pending: StatementLine? = null
        lines.forEach { line ->
            val dateMatch = findDate(line)
            if (dateMatch == null) {
                // A continuation line (wrapped purpose / payee) belongs to the row above.
                pending?.let { p ->
                    if (!skipLine.containsMatchIn(line) && p.description.length < 100 && amount.find(line) == null) {
                        pending = p.copy(description = "${p.description} $line".trim())
                    }
                }
                return@forEach
            }
            pending?.let { out += it }
            pending = null
            if (skipLine.containsMatchIn(line)) return@forEach

            val date = dateMatch.first
            // Blank out every date (booking + value date columns) so they can't pass for amounts.
            val rest = line.replace(iso, " ").replace(dmy, " ").replace(named, " ")
            val m = amount.find(rest) ?: return@forEach
            val value = WalletParser.toNumber(m.groupValues[2]) ?: return@forEach
            if (value == 0.0) return@forEach
            val sign = m.groupValues[1].trim()
            val suffix = m.groupValues[4].trim()
            // Strip any further dates (value date column) and amounts (running balance column).
            val desc = rest.substring(0, m.range.first)
                .replace(Regex("""\s+"""), " ").trim(' ', '|', '·', '-')
            val explicitNegative = sign == "-" || sign == "−" || suffix == "-" || suffix == "S"
            val explicitPositive = sign == "+" || suffix == "+" || suffix == "H"
            val income = when {
                explicitNegative -> false
                explicitPositive -> true
                else -> incomeWords.containsMatchIn(desc)
            }
            pending = StatementLine(date, value, income, desc.ifEmpty { "Bank transaction" })
        }
        pending?.let { out += it }
        return out.map { it.copy(description = it.description.take(120)) }
    }

    private fun findDate(line: String): Pair<LocalDate, IntRange>? {
        iso.find(line)?.let { m -> parseIso(m)?.let { return it to m.range } }
        dmy.find(line)?.let { m -> parseDmy(m)?.let { return it to m.range } }
        named.find(line)?.let { m -> parseNamed(m.value)?.let { return it to m.range } }
        return null
    }

    internal fun parseDate(raw: String): LocalDate? {
        val s = raw.trim().trim('"')
        iso.find(s)?.let { return parseIso(it) }
        dmy.find(s)?.let { return parseDmy(it) }
        named.find(s)?.let { return parseNamed(it.value) }
        return null
    }

    private fun parseIso(m: MatchResult) = runCatching {
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }.getOrNull()

    private fun parseDmy(m: MatchResult) = runCatching {
        val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
        LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt())
    }.getOrNull()

    private val germanMonths = mapOf("mär" to "mar", "mai" to "may", "okt" to "oct", "dez" to "dec")

    private fun parseNamed(raw: String): LocalDate? {
        val parts = raw.replace(".", " ").split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val mon = parts[1].take(3).lowercase().let { germanMonths[it] ?: it }.replaceFirstChar { it.uppercase() }
        return runCatching {
            LocalDate.parse("${parts[0].padStart(2, '0')} $mon ${parts[2]}", DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))
        }.getOrNull()
    }
}
