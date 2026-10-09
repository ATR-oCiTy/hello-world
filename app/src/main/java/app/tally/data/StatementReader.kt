package app.tally.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.tally.logic.Glyph
import app.tally.logic.StatementLine
import app.tally.logic.StatementParser
import app.tally.logic.UniCreditStatement
import app.tally.logic.Words
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/** Reads a picked statement file (PDF or CSV) into transactions. */
object StatementReader {

    fun read(context: Context, uri: Uri): List<StatementLine> {
        val resolver = context.contentResolver
        val type = resolver.getType(uri).orEmpty()
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()

        if (type == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
            PDFBoxResourceLoader.init(context.applicationContext)
            return resolver.openInputStream(uri)!!.use { input ->
                PDDocument.load(input).use { doc -> readPdf(doc) }
            }
        }

        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val utf8 = bytes.toString(Charsets.UTF_8)
        // German bank CSVs are often Latin-1; broken umlauts show up as replacement chars.
        val text = if ('�' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
        return StatementParser.parse(text)
    }

    private fun readPdf(doc: PDDocument): List<StatementLine> {
        // Collect every character with its position; the column-aware parser needs them.
        val glyphs = mutableListOf<Glyph>()
        val text = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                positions.forEach { t ->
                    glyphs += Glyph(currentPageNo, t.xDirAdj, t.yDirAdj, t.widthDirAdj, t.widthOfSpace, t.unicode)
                }
                super.writeString(text, positions)
            }
        }.apply { sortByPosition = true }.getText(doc)

        return UniCreditStatement.parse(Words.fromGlyphs(glyphs))?.takeIf { it.isNotEmpty() }
            ?: StatementParser.parse(text)
    }
}
