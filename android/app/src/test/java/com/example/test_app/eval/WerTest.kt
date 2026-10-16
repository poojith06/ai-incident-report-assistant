package com.example.test_app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WerTest {
    private val eps = 1e-9

    @Test fun identicalTextsHaveZeroErrors() {
        val r = Wer.compute("There was a water leak near Conference Room 2", "there was a water leak near conference room 2")
        assertEquals(0, r.errors)
        assertEquals(9, r.referenceWords)
        assertEquals(0.0, r.wer, eps)
    }

    @Test fun oneDeletionOfSix() {
        val r = Wer.compute("the cat sat on the mat", "the cat sat on mat")
        assertEquals(WerResult(substitutions = 0, deletions = 1, insertions = 0, referenceWords = 6), r)
        assertEquals(1.0 / 6, r.wer, eps)
    }

    @Test fun twoSubstitutionsOfFive() {
        // a -> the, leak -> lake
        val r = Wer.compute("there was a water leak", "there was the water lake")
        assertEquals(WerResult(2, 0, 0, 5), r)
        assertEquals(0.4, r.wer, eps)
    }

    @Test fun oneInsertionOfFour() {
        val r = Wer.compute("turn on the light", "turn on the light please")
        assertEquals(WerResult(0, 0, 1, 4), r)
        assertEquals(0.25, r.wer, eps)
    }

    @Test fun substitutionPlusDeletionSplit() {
        // a b c d -> a x c : b became x (S), d is missing (D)
        val r = Wer.compute("a b c d", "a x c")
        assertEquals(WerResult(1, 1, 0, 4), r)
        assertEquals(0.5, r.wer, eps)
    }

    @Test fun werCanExceedOne() {
        val r = Wer.compute("hello", "hello there my friend")
        assertEquals(WerResult(0, 0, 3, 1), r)
        assertEquals(3.0, r.wer, eps)
    }

    @Test fun everythingWrongIsOneHundredPercent() {
        assertEquals(1.0, Wer.compute("a b c", "x y z").wer, eps)
    }

    @Test fun emptyHypothesisDeletesEveryWord() {
        val r = Wer.compute("water leak", "")
        assertEquals(WerResult(0, 2, 0, 2), r)
        assertEquals(1.0, r.wer, eps)
    }

    @Test fun emptyReferenceRate() {
        assertEquals(0.0, Wer.compute("", "").wer, eps)
        assertEquals(1.0, Wer.compute("", "anything").wer, eps)
    }

    @Test fun normalisationIgnoresCasePunctuationApostrophesNumberWordsAndAmPm() {
        assertEquals(0, Wer.compute("It's in Conference Room 2, around 10 A.M.!", "its in conference room two around 10 am").errors)
        assertEquals(0, Wer.compute("Don't touch it", "dont touch it").errors)
    }

    @Test fun differentNumbersStillCount() {
        assertEquals(1, Wer.compute("room 2", "room 3").errors)
    }

    @Test fun meanAndCorpusWeighDifferently() {
        val short = WerResult(1, 0, 0, 2) // 50 %
        val long = WerResult(0, 0, 0, 8) // 0 %
        assertEquals(0.25, Wer.mean(listOf(short, long))!!, eps) // every item weighs the same
        assertEquals(0.1, Wer.corpus(listOf(short, long))!!, eps) // 1 error in 10 words
    }

    @Test fun aggregatesOfNothingAreNull() {
        assertNull(Wer.mean(emptyList()))
        assertNull(Wer.corpus(emptyList()))
        assertNull(Wer.corpus(listOf(WerResult.ZERO)))
        assertNotNull(Wer.mean(listOf(WerResult.ZERO)))
    }

    @Test fun resultsAdd() {
        assertEquals(WerResult(1, 1, 1, 6), WerResult(1, 0, 0, 2) + WerResult(0, 1, 1, 4))
    }
}
