package app.tally.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.tally.logic.Answer
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * An optional language model that runs entirely on the phone (MediaPipe + a .task model such as
 * Gemma 3 1B). You download the model file yourself and pick it in Settings; it's copied into
 * app storage because the inference engine needs a real file path.
 */
object LocalLlm {

    private const val FILE = "assistant-model.task"

    @Volatile private var engine: LlmInference? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun isInstalled(context: Context): Boolean = file(context).let { it.exists() && it.length() > 10_000_000 }

    fun sizeMb(context: Context): Long = file(context).length() / 1_000_000

    /** Copies the picked model into app storage, reporting progress from 0 to 1. */
    suspend fun install(context: Context, uri: Uri, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val total = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
        val tmp = File(context.filesDir, "$FILE.part")
        resolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (total > 0) onProgress((copied.toDouble() / total).toFloat())
                }
            }
        }
        close()
        file(context).delete()
        check(tmp.renameTo(file(context))) { "Couldn't save the model" }
    }

    fun remove(context: Context) {
        close()
        file(context).delete()
    }

    @Synchronized
    fun close() {
        engine?.close()
        engine = null
    }

    @Synchronized
    private fun load(context: Context): LlmInference = engine ?: LlmInference.createFromOptions(
        context.applicationContext,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file(context).absolutePath)
            .setMaxTokens(2048)
            .build(),
    ).also { engine = it }

    suspend fun generate(context: Context, prompt: String): String = withContext(Dispatchers.Default) {
        load(context).generateResponse(prompt).trim()
    }

    /**
     * Gemma chat format. The model gets the exact figures Tally computed plus a summary of your
     * finances, and is told to use only those, because small models can't do arithmetic reliably.
     */
    fun prompt(summary: String, computed: Answer, question: String): String = buildString {
        append("<start_of_turn>user\n")
        append("You are Tally, a friendly, concise personal-finance assistant inside a budgeting app. ")
        append("Answer the question using ONLY the facts below. Never invent or recalculate numbers; ")
        append("copy figures exactly as written. If the facts don't contain the answer, say so briefly. ")
        append("Reply in at most 3 short sentences.\n\n")
        append("FACTS:\n").append(summary).append("\n")
        if (computed.understood) append("Exact answer computed by the app: ").append(computed.text).append("\n")
        append("\nQUESTION: ").append(question)
        append("<end_of_turn>\n<start_of_turn>model\n")
    }
}
