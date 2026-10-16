package com.example.test_app.knowledgegraph.admin.graphview

/**
 * Deterministic force-directed layout (Fruchterman-Reingold, plain O(n^2) repulsion, linear cooling) for the admin graph view.
 * Pure Kotlin so it is JVM-tested; the UI calls [step] a few times per frame from a coroutine and draws [x] and [y].
 *
 * The world is the box [0, [width]] x [0, [height]]; every coordinate stays inside it and finite (never NaN). Initial positions come from a
 * hash of the node id (a spiral), so the same node starts at the same place whatever subgraph it is part of: a cache of earlier
 * positions ([initial]) keeps the picture stable when filters change. A weak pull to the centre keeps disconnected components on screen.
 * Pinned nodes (a drag or a long-press) never move by force.
 *
 * @param ids node ids in drawing order (index = node index)
 * @param edges undirected index pairs; self loops, out-of-range and duplicate pairs are ignored
 * @param initial known positions by id (from a cache); unknown ids get their hash position
 */
class ForceLayout(
    val ids: List<String>,
    edges: List<Pair<Int, Int>>,
    val width: Double = 1000.0,
    val height: Double = 1000.0,
    initial: Map<String, Pair<Double, Double>> = emptyMap(),
    seed: Long = 7L,
    private val maxIterations: Int = 300,
) {
    val n: Int = ids.size
    val x = DoubleArray(n)
    val y = DoubleArray(n)
    val pinned = BooleanArray(n)
    private val dx = DoubleArray(n)
    private val dy = DoubleArray(n)
    private val pairs: List<Pair<Int, Int>> = edges.filter { (a, b) -> a != b && a in 0 until n && b in 0 until n }
        .map { (a, b) -> if (a < b) a to b else b to a }.distinct()
    private val k: Double = if (n == 0) 1.0 else 0.75 * Math.sqrt(width * height / n)
    private val startTemperature = Math.min(width, height) / 8.0

    /** Iterations done so far. */
    var iteration: Int = 0
        private set

    /** Current maximum step length; shrinks while cooling. */
    var temperature: Double = startTemperature
        private set

    /** True once the layout has cooled or [maxIterations] steps were taken. */
    val finished: Boolean get() = n < 2 || iteration >= maxIterations || temperature < 0.5

    init {
        for (i in 0 until n) {
            val known = initial[ids[i]]
            if (known != null && known.first.isFinite() && known.second.isFinite()) {
                x[i] = known.first.coerceIn(0.0, width); y[i] = known.second.coerceIn(0.0, height)
            } else {
                val h = hash(ids[i], seed)
                val angle = (h and 0xFFFFL) / 65536.0 * 2 * Math.PI
                val radius = (((h ushr 16) and 0xFFFFL) / 65536.0) * 0.4 + 0.05
                x[i] = width / 2 + Math.cos(angle) * radius * width
                y[i] = height / 2 + Math.sin(angle) * radius * height
            }
        }
        if (n == 1) { x[0] = width / 2; y[0] = height / 2 }
    }

    /** One iteration. Returns the largest displacement made (0 when nothing moves). */
    fun step(): Double {
        if (n < 2) { iteration++; return 0.0 }
        java.util.Arrays.fill(dx, 0.0); java.util.Arrays.fill(dy, 0.0)
        for (i in 0 until n) for (j in i + 1 until n) {
            var ddx = x[i] - x[j]; var ddy = y[i] - y[j]
            var d2 = ddx * ddx + ddy * ddy
            if (d2 < 1e-6) { // exactly on top of each other: separate deterministically
                ddx = ((i * 31 + j) % 7 - 3) * 0.1 + 0.05; ddy = ((i * 17 + j) % 5 - 2) * 0.1 + 0.05; d2 = ddx * ddx + ddy * ddy
            }
            val d = Math.sqrt(d2)
            val f = k * k / d
            val fx = ddx / d * f; val fy = ddy / d * f
            dx[i] += fx; dy[i] += fy; dx[j] -= fx; dy[j] -= fy
        }
        for ((a, b) in pairs) {
            val ddx = x[a] - x[b]; val ddy = y[a] - y[b]
            val d = Math.max(Math.sqrt(ddx * ddx + ddy * ddy), 1e-3)
            val f = d * d / k
            val fx = ddx / d * f; val fy = ddy / d * f
            dx[a] -= fx; dy[a] -= fy; dx[b] += fx; dy[b] += fy
        }
        var maxMove = 0.0
        val cx = width / 2; val cy = height / 2
        for (i in 0 until n) {
            if (pinned[i]) continue
            dx[i] += (cx - x[i]) * GRAVITY; dy[i] += (cy - y[i]) * GRAVITY
            val len = Math.sqrt(dx[i] * dx[i] + dy[i] * dy[i])
            if (len < 1e-9 || !len.isFinite()) continue
            val move = Math.min(len, temperature)
            val nx = (x[i] + dx[i] / len * move).coerceIn(0.0, width)
            val ny = (y[i] + dy[i] / len * move).coerceIn(0.0, height)
            maxMove = Math.max(maxMove, Math.hypot(nx - x[i], ny - y[i]))
            x[i] = nx; y[i] = ny
        }
        iteration++
        temperature = Math.max(0.0, startTemperature * (1.0 - iteration.toDouble() / maxIterations))
        return maxMove
    }

    /** Runs up to [steps] iterations (stops early when [finished]); returns how many ran. */
    fun run(steps: Int = maxIterations): Int {
        var done = 0
        while (done < steps && !finished) { step(); done++ }
        return done
    }

    /** Fixes node [i] at ([px], [py]) (clamped into the world): a drag or a long-press pin. */
    fun pin(i: Int, px: Double, py: Double) {
        if (i !in 0 until n) return
        pinned[i] = true; x[i] = px.coerceIn(0.0, width); y[i] = py.coerceIn(0.0, height)
    }

    fun unpin(i: Int) { if (i in 0 until n) pinned[i] = false }

    /** Restarts the cooling (after a drag or a filter change) so the rest of the graph relaxes around the moved node. */
    fun reheat(fraction: Double = 0.5) {
        iteration = (maxIterations * (1.0 - fraction.coerceIn(0.0, 1.0))).toInt().coerceAtLeast(0).coerceAtMost(maxIterations - 1)
        temperature = startTemperature * fraction.coerceIn(0.05, 1.0)
    }

    /** Current positions by id (for the position cache). */
    fun positions(): Map<String, Pair<Double, Double>> = ids.indices.associate { ids[it] to (x[it] to y[it]) }

    private fun hash(id: String, seed: Long): Long {
        var h = seed xor -0x61c8864680b583ebL
        for (c in id) { h = (h xor c.code.toLong()) * 0x100000001b3L; h = h xor (h ushr 29) }
        return h and Long.MAX_VALUE
    }

    companion object {
        private const val GRAVITY = 0.02
    }
}
