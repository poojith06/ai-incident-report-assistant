package com.example.test_app.admin

import com.example.test_app.knowledgegraph.admin.graphview.ForceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure force-directed layout of the admin graph (ADR-058). */
class ForceLayoutTest {
    private fun ring(n: Int) = (0 until n).map { it to (it + 1) % n }
    private fun ids(n: Int) = (0 until n).map { "n$it" }

    private fun allFiniteAndInside(l: ForceLayout) {
        for (i in 0 until l.n) {
            assertTrue("x[$i]=${l.x[i]}", l.x[i].isFinite() && l.x[i] in 0.0..l.width)
            assertTrue("y[$i]=${l.y[i]}", l.y[i].isFinite() && l.y[i] in 0.0..l.height)
        }
    }

    @Test fun theSameInputGivesTheSamePositions() {
        val a = ForceLayout(ids(30), ring(30)).also { it.run() }
        val b = ForceLayout(ids(30), ring(30)).also { it.run() }
        assertTrue(a.x.contentEquals(b.x)); assertTrue(a.y.contentEquals(b.y))
        assertEquals(a.iteration, b.iteration)
    }

    @Test fun anotherSeedGivesAnotherStart() {
        val a = ForceLayout(ids(10), ring(10), seed = 1)
        val b = ForceLayout(ids(10), ring(10), seed = 2)
        assertFalse(a.x.contentEquals(b.x))
    }

    @Test fun coordinatesStayInsideTheWorldAndFiniteForEverySize() {
        for (n in listOf(0, 1, 2, 3, 10, 80, 250)) {
            val l = ForceLayout(ids(n), if (n > 1) ring(n) else emptyList())
            allFiniteAndInside(l)
            l.run(); allFiniteAndInside(l)
        }
    }

    @Test fun zeroOneAndTwoNodesAreHandled() {
        val empty = ForceLayout(emptyList(), emptyList())
        assertTrue(empty.finished); assertEquals(0.0, empty.step(), 0.0); assertTrue(empty.positions().isEmpty())
        val one = ForceLayout(listOf("a"), emptyList())
        one.run(); assertEquals(500.0, one.x[0], 1e-9); assertEquals(500.0, one.y[0], 1e-9)
        val two = ForceLayout(listOf("a", "b"), listOf(0 to 1)).also { it.run() }
        allFiniteAndInside(two)
        assertTrue(Math.hypot(two.x[0] - two.x[1], two.y[0] - two.y[1]) > 1.0)
    }

    @Test fun theLayoutCoolsAndFinishesWithinTheIterationCap() {
        val l = ForceLayout(ids(60), ring(60), maxIterations = 120)
        val ran = l.run(10_000)
        assertTrue(ran <= 120); assertTrue(l.finished); assertTrue(l.temperature < 1.0)
        assertEquals(0, l.run(50))
    }

    @Test fun movementShrinksAsItConverges() {
        val l = ForceLayout(ids(40), ring(40))
        val first = (1..5).maxOf { l.step() }
        l.run(250)
        val last = l.step()
        assertTrue("first=$first last=$last", last < first)
    }

    @Test fun linkedNodesEndUpCloserThanUnlinkedOnes() {
        // two separate rings: members of a ring are linked, nodes of different rings are not
        val edges = ring(8) + ring(8).map { (a, b) -> (a + 8) to (b + 8) }
        val l = ForceLayout(ids(16), edges).also { it.run() }
        fun d(a: Int, b: Int) = Math.hypot(l.x[a] - l.x[b], l.y[a] - l.y[b])
        val linked = edges.map { (a, b) -> d(a, b) }.average()
        val unlinked = (0 until 8).flatMap { a -> (8 until 16).map { b -> d(a, b) } }.average()
        assertTrue("linked=$linked unlinked=$unlinked", linked < unlinked)
    }

    @Test fun disconnectedComponentsAndIsolatedNodesStayOnScreenAndApart() {
        val l = ForceLayout(ids(12), listOf(0 to 1, 1 to 2, 5 to 6)).also { it.run() }
        allFiniteAndInside(l)
        val pts = (0 until 12).map { l.x[it] to l.y[it] }
        assertEquals(12, pts.distinct().size)
        for (i in 0 until 12) for (j in i + 1 until 12) assertTrue(Math.hypot(l.x[i] - l.x[j], l.y[i] - l.y[j]) > 0.5)
    }

    @Test fun aPinnedNodeNeverMovesByForce() {
        val l = ForceLayout(ids(20), ring(20))
        l.pin(3, 123.0, 456.0)
        l.run()
        assertEquals(123.0, l.x[3], 0.0); assertEquals(456.0, l.y[3], 0.0)
        l.reheat(); l.run()
        assertEquals(123.0, l.x[3], 0.0); assertEquals(456.0, l.y[3], 0.0)
        l.unpin(3); l.reheat(1.0); l.run()
        assertTrue(l.x[3] != 123.0 || l.y[3] != 456.0)
    }

    @Test fun pinningClampsIntoTheWorldAndIgnoresBadIndexes() {
        val l = ForceLayout(ids(3), ring(3))
        l.pin(0, -50.0, 5000.0)
        assertEquals(0.0, l.x[0], 0.0); assertEquals(l.height, l.y[0], 0.0)
        l.pin(99, 1.0, 1.0); l.unpin(-1)
        allFiniteAndInside(l)
    }

    @Test fun knownPositionsFromTheCacheAreTheStartingPoint() {
        val l = ForceLayout(listOf("a", "b", "c"), listOf(0 to 1), initial = mapOf("a" to (10.0 to 20.0), "b" to (Double.NaN to 5.0), "c" to (2000.0 to -5.0)))
        assertEquals(10.0, l.x[0], 0.0); assertEquals(20.0, l.y[0], 0.0)
        assertTrue(l.x[1].isFinite()) // NaN in the cache is ignored
        assertEquals(l.width, l.x[2], 0.0); assertEquals(0.0, l.y[2], 0.0) // clamped
        assertEquals(setOf("a", "b", "c"), l.positions().keys)
    }

    @Test fun theStartPositionOfANodeDoesNotDependOnTheOtherNodes() {
        val a = ForceLayout(listOf("x", "y", "z"), emptyList())
        val b = ForceLayout(listOf("q", "z"), emptyList())
        assertEquals(a.x[2], b.x[1], 0.0); assertEquals(a.y[2], b.y[1], 0.0)
    }

    @Test fun selfLoopsDuplicatesAndOutOfRangeEdgesAreIgnored() {
        val l = ForceLayout(ids(4), listOf(0 to 0, 0 to 1, 1 to 0, 0 to 1, 9 to 1, -1 to 2, 2 to 3)).also { it.run() }
        allFiniteAndInside(l)
    }

    @Test fun reheatRestartsTheCooling() {
        val l = ForceLayout(ids(10), ring(10)); l.run()
        assertTrue(l.finished)
        l.reheat(0.5)
        assertFalse(l.finished); assertTrue(l.temperature > 1.0)
        assertTrue(l.run(5) > 0)
    }

    @Test fun twoHundredNodesLayOutQuickly() {
        val n = 250
        val edges = (0 until n).flatMap { i -> listOf(i to (i + 1) % n, i to (i * 7 + 3) % n) }
        val start = System.nanoTime()
        ForceLayout(ids(n), edges).run()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 4_000)
    }
}
