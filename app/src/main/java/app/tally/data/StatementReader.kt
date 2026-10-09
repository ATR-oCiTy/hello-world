package app.tally.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** Turns a picked statement file (PDF or CSV/text) into plain text for [app.tally.logic.StatementParser]. */
object StatementReader {

    fun readText(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val type = resolver.getType(uri).orEmpty()
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }.orEmpty()

        if (type == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
            PDFBoxResourceLoader.init(context.applicationContext)
            return resolver.openInputStream(uri)!!.use { input ->
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().apply { sortByPosition = true }.getText(doc)
                }
            }
        }

        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val utf8 = bytes.toString(Charsets.UTF_8)
        // German bank CSVs are often Latin-1; broken umlauts show up as replacement chars.
        return if ('�' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
    }
}
