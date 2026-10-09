package app.tally.logic

import kotlin.math.abs

/** One character from a PDF page, as the PDF library reports it. */
data class Glyph(val page: Int, val x: Float, val y: Float, val width: Float, val spaceWidth: Float, val text: String)

/** A word on a page; [x] is where it starts. */
data class Word(val page: Int, val x: Float, val y: Float, val text: String)

object Words {
    /** Joins glyphs into words, splitting on whitespace and on visible gaps between letters. */
    fun fromGlyphs(glyphs: List<Glyph>): List<Word> {
        val out = mutableListOf<Word>()
        var cur: StringBuilder? = null
        var start: Glyph? = null
        var prev: Glyph? = null
        fun flush() {
            val s = start
            val c = cur
            if (s != null && c != null && c.isNotBlank()) out += Word(s.page, s.x, s.y, c.toString())
            cur = null
            start = null
        }
        glyphs.forEach { g ->
            val p = prev
            val gapLimit = (if (g.spaceWidth > 0f) g.spaceWidth else g.width) * 0.5f
            val newLine = p == null || p.page != g.page || abs(p.y - g.y) > 1.5f
            val gap = if (p == null) 0f else g.x - (p.x + p.width)
            if (g.text.isBlank()) {
                flush()
            } else {
                if (newLine || gap > gapLimit || gap < -p!!.width * 2) flush()
                if (cur == null) { cur = StringBuilder(); start = g }
                cur!!.append(g.text)
            }
            prev = g
        }
        flush()
        return out
    }
}

/**
 * Reads the UniCredit / Aion Bank statement that Expatrio accounts produce.
 *
 * Each transaction spans 2–4 lines across seven columns (dates, participant, card, operation
 * title, operation type, amount, balance), and foreign-currency payments show the original
 * amount, the EUR amount and an exchange-rate line. Plain text extraction mashes the columns
 * together, so this works from word positions instead, using the header row to find each column.
 */
object UniCreditStatement {

    private enum class Col { DATE, PARTICIPANT, CARD, TITLE, TYPE, AMOUNT, BALANCE }

    private data class Line(val page: Int, val y: Float, val cells: Map<Col, String>)

    private val date = Regex("""^(\d{2})\.(\d{2})\.(\d{4})$""")
    private val money = Regex("""^([-+]?\d{1,3}(?: \d{3})*\.\d{2}) ([A-Z]{3})$""")
    private val iban = Regex("""^[A-Z]{2}\d{2}[A-Z0-9]{8,}$""")
    private val location = Regex(""",\s*[A-Z]{2}$""")

    /** Null when this doesn't look like a UniCredit statement. */
    fun parse(words: List<Word>): List<StatementLine>? {
        val header = words.firstOrNull { it.text == "Participant" } ?: return null
        val headerWords = words.filter { it.page == header.page && abs(it.y - header.y) < 20f }
        fun x(label: String) = headerWords.firstOrNull { it.text == label }?.x
        val participantX = header.x
        val cardX = x("Card") ?: return null
        val amountX = x("Amount") ?: return null
        val balanceX = x("Balance") ?: return null
        val operationXs = headerWords.filter { it.text == "Operation" }.map { it.x }.distinct().sorted()
        if (operationXs.size < 2) return null
        val (titleX, typeX) = operationXs[0] to operationXs[1]
        val amountCut = amountX - (balanceX - amountX) * 0.5f
        val balanceCut = (amountX + balanceX) / 2f + (balanceX - amountX) * 0.1f

        fun colOf(x: Float): Col = when {
            x < participantX - 2 -> Col.DATE
            x < cardX - 2 -> Col.PARTICIPANT
            x < titleX - 2 -> Col.CARD
            x < typeX - 2 -> Col.TITLE
            x < amountCut -> Col.TYPE
            x < balanceCut -> Col.AMOUNT
            else -> Col.BALANCE
        }

        // Group words into visual lines, then each line's words into columns.
        val lines = words
            .filter { it.page > header.page || it.y > header.y + 1f }
            .groupBy { it.page to Math.round(it.y) }
            .values
            .map { ws ->
                val sorted = ws.sortedBy { it.x }
                Line(
                    page = sorted[0].page,
                    y = sorted[0].y,
                    cells = sorted.groupBy { colOf(it.x) }.mapValues { (_, v) -> v.joinToString(" ") { it.text } },
                )
            }
            .sortedWith(compareBy({ it.page }, { it.y }))

        // A transaction starts on the line carrying its running balance.
        val records = mutableListOf<MutableList<Line>>()
        var inFooter = false
        lines.forEach { line ->
            val text = line.cells.values.joinToString(" ")
            val isStart = line.cells[Col.BALANCE]?.let { money.matches(it) } == true &&
                line.cells[Col.DATE]?.let { date.matches(it.substringBefore(' ')) } == true
            when {
                isStart -> { records += mutableListOf(line); inFooter = false }
                text.startsWith("Issued on") || text.startsWith("Page ") -> inFooter = true
                !inFooter && records.isNotEmpty() && line.page == records.last().last().page -> records.last() += line
            }
        }

        return records.mapNotNull(::toLine)
    }

    private fun toLine(rec: List<Line>): StatementLine? {
        fun col(c: Col) = rec.mapNotNull { it.cells[c] }
        val d = date.find(col(Col.DATE).first().substringBefore(' ')) ?: return null
        val txDate = runCatching {
            java.time.LocalDate.of(d.groupValues[3].toInt(), d.groupValues[2].toInt(), d.groupValues[1].toInt())
        }.getOrNull() ?: return null

        // The EUR figure is either on the first line or, for foreign currency, the line below.
        val eur = col(Col.AMOUNT)
            .filterNot { '=' in it }
            .mapNotNull { money.find(it.trim()) }
            .firstOrNull { it.groupValues[2] == "EUR" } ?: return null
        val signed = eur.groupValues[1].replace(" ", "").toDoubleOrNull() ?: return null
        if (signed == 0.0) return null

        val type = col(Col.TYPE).firstOrNull().orEmpty()
            .replace(Regex("""\b[0-9a-f]{6,}-.*$"""), "").trim()
        val title = col(Col.TITLE).joinToString(" ").trim()
        val participant = payeeName(col(Col.PARTICIPANT))
        val card = col(Col.CARD).firstOrNull()?.removePrefix("*")?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val balance = rec.first().cells[Col.BALANCE]?.let { money.find(it) }?.groupValues?.get(1)
            ?.replace(" ", "")?.toDoubleOrNull()

        val isCard = type.startsWith("Card", ignoreCase = true) || card != null
        val internal = type.startsWith("Internal", ignoreCase = true)
        val merchant = prettify(
            when {
                isCard || internal -> title
                else -> participant.ifEmpty { title }
            },
        ).ifEmpty { type.ifEmpty { "Bank transaction" } }
        val note = if (isCard) "" else title.takeIf { it != merchant }.orEmpty()

        return StatementLine(
            date = txDate,
            amount = abs(signed),
            income = signed > 0,
            description = listOf(merchant, note).filter { it.isNotEmpty() }.joinToString(" · "),
            merchant = merchant.take(40),
            note = note,
            card = card,
            kind = type,
            balanceAfter = balance,
        )
    }

    private val streetWord = Regex(
        """(?i)(str\.?|straße|strasse|allee|weg|gasse|platz|ring|damm|avenue|road|street|lane)$""",
    )
    private val standaloneStreet = setOf("avenue", "road", "street", "lane", "str", "str.", "weg", "platz", "allee")
    private val streetPrefix = setOf("am", "an", "im", "auf", "zum", "zur")
    private val companySuffix = setOf("ag", "gmbh", "ggmbh", "mbh", "se", "kg", "ohg", "ug", "limited", "ltd", "inc", "llc", "e.v", "s.c.a", "gbr")

    /**
     * Transfers put the IBAN (wrapped over two lines) first, then the name, then often a street
     * address, all squeezed into one narrow column. Keep just the name.
     */
    internal fun payeeName(lines: List<String>): String {
        val tokens = lines
            .filterNot { location.containsMatchIn(it) }
            .flatMap { it.split(' ') }
            .filter { it.isNotEmpty() }
            .dropWhile { iban.matches(it) || (it.any(Char::isDigit) && it.none(Char::isLowerCase)) }
        val name = mutableListOf<String>()
        for (t in tokens) {
            val bare = t.trimEnd(',', '.')
            when {
                t.any(Char::isDigit) -> break
                bare.lowercase() in standaloneStreet -> { name.removeLastOrNull(); break }
                streetWord.containsMatchIn(bare) && bare.length > 5 -> break
                t == "CO" || t == "C/O" || t == "-" -> break
                name.isNotEmpty() && bare.lowercase() in streetPrefix -> break
                bare.lowercase() in companySuffix -> { name += bare; break }
                else -> name += t
            }
        }
        return name.joinToString(" ").replace(Regex("""\s*\(\w{0,3}$"""), "").trim(',', ' ')
    }

    // Payment processors glue their code onto the merchant: "NYX*Dallmayr", "SumUp *Kiosk".
    private val processorPrefix = Regex("""^(?:[A-Z]{2,6}\*(?=\S)|(?:SumUp|SUMUP|Sumup)\s?\*\s?)""")
    private val noiseSuffix = Regex("""(?:\s+(?:PENDING|\d{3,}))+$|[\s/*-]+$""")

    /** "NYX*AloisDallmayrAutom" → "AloisDallmayrAutom", "ALDI SUED" → "Aldi Sued", "Rossmann 3572" → "Rossmann". */
    internal fun prettify(raw: String): String {
        var s = raw.trim().replace(processorPrefix, "").replace(Regex("""\s*\*\s*"""), " ")
            .replace(noiseSuffix, "").trim()
        val letters = s.filter(Char::isLetter)
        if (letters.length > 3 && letters.count(Char::isUpperCase) >= letters.length * 0.8) {
            s = s.split(' ').joinToString(" ") { w ->
                if (w.count(Char::isLetter) <= 2) w else w.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        return s.replace(Regex("""\s{2,}"""), " ")
    }
}
