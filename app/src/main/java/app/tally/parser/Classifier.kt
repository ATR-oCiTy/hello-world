package app.tally.parser

import app.tally.data.Category

/** Per-merchant category scores, built up from the user's own tagging. */
typealias Learned = Map<String, Map<Category, Double>>

/**
 * Picks a category for a merchant: what you've taught it first, then the built-in keyword rules.
 *
 * Each time you tag a merchant, that category gains a point and the others are halved, so the
 * most recent tag wins straight away but a long habit isn't erased by one odd purchase.
 */
object Classifier {

    private const val DECAY = 0.5
    private const val MIN_PREFIX = 4

    /** "STARBUCKS #1234 LONDON" and "Starbucks 1234 London" both become "starbucks london". */
    fun key(merchant: String): String =
        merchant.lowercase()
            .replace(Regex("""[^\p{L}&]+"""), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    fun classify(merchant: String, learned: Learned): Category =
        learnedCategory(merchant, learned) ?: WalletParser.guessCategory(merchant)

    fun learnedCategory(merchant: String, learned: Learned): Category? {
        val k = key(merchant)
        if (k.isEmpty()) return null
        learned[k]?.best()?.let { return it }

        // Fall back to other branches of the same brand: "Starbucks Victoria" learns from "Starbucks Soho".
        val brand = k.substringBefore(' ')
        if (brand.length < MIN_PREFIX) return null
        val merged = mutableMapOf<Category, Double>()
        learned.forEach { (other, scores) ->
            if (other.substringBefore(' ') == brand) {
                scores.forEach { (c, s) -> merged[c] = (merged[c] ?: 0.0) + s }
            }
        }
        return merged.best()
    }

    fun teach(learned: Learned, merchant: String, category: Category): Learned {
        val k = key(merchant)
        if (k.isEmpty()) return learned
        val decayed = (learned[k] ?: emptyMap()).mapValues { (_, s) -> s * DECAY }
        val updated = decayed + (category to (decayed[category] ?: 0.0) + 1.0)
        return learned + (k to updated)
    }

    fun forget(learned: Learned, key: String): Learned = learned - key

    private fun Map<Category, Double>.best(): Category? = maxByOrNull { it.value }?.key
}
