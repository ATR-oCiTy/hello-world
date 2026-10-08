package app.tally.parser

import app.tally.data.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassifierTest {

    @Test fun keyIgnoresStoreNumbersAndCase() {
        assertEquals("starbucks london", Classifier.key("STARBUCKS #1234 LONDON"))
        assertEquals("starbucks london", Classifier.key("Starbucks 1234 London"))
        assertEquals("h&m", Classifier.key("H&M"))
    }

    @Test fun fallsBackToKeywords() {
        assertEquals(Category.FOOD, Classifier.classify("Starbucks", emptyMap()))
        assertEquals(Category.OTHER, Classifier.classify("Zorblax Ltd", emptyMap()))
    }

    @Test fun oneTagOverridesKeywords() {
        val learned = Classifier.teach(emptyMap(), "Zorblax Ltd", Category.FUN)
        assertEquals(Category.FUN, Classifier.classify("ZORBLAX LTD", learned))
        // Even beats a built-in rule.
        val l2 = Classifier.teach(emptyMap(), "Amazon", Category.BILLS)
        assertEquals(Category.BILLS, Classifier.classify("Amazon", l2))
    }

    @Test fun latestTagWinsButHabitsAreKept() {
        var l: Learned = emptyMap()
        repeat(3) { l = Classifier.teach(l, "Corner Shop", Category.GROCERIES) }
        l = Classifier.teach(l, "Corner Shop", Category.FOOD)
        assertEquals(Category.FOOD, Classifier.classify("Corner Shop", l))
        assertEquals(true, (l["corner shop"]!![Category.GROCERIES] ?: 0.0) > 0.0)
    }

    @Test fun learnsAcrossBranchesOfSameBrand() {
        val l = Classifier.teach(emptyMap(), "Pret Soho", Category.SHOPPING)
        assertEquals(Category.SHOPPING, Classifier.learnedCategory("Pret Victoria", l))
    }

    @Test fun shortBrandsDoNotSpread() {
        val l = Classifier.teach(emptyMap(), "The Ivy", Category.FUN)
        assertNull(Classifier.learnedCategory("The Shard", l))
    }

    @Test fun forget() {
        val l = Classifier.teach(emptyMap(), "Zorblax", Category.FUN)
        assertNull(Classifier.learnedCategory("Zorblax", Classifier.forget(l, "zorblax")))
    }
}
