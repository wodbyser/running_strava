package com.running.load

import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Small pure statistics helpers for the load analysis. Every random step uses a fixed seed, so pages are reproducible. */
object LoadStats {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

    /** Population standard deviation (n), as used by Foster's monotony. */
    fun populationSd(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }

    /** Linear interpolation between order statistics (type 7), [p] in 0..1. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val pos = p.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    data class Interval(val median: Double, val low: Double, val high: Double, val n: Int) {
        val excludesZero: Boolean get() = low > 0.0 || high < 0.0
    }

    /** Median with a percentile bootstrap interval (95 %). Null for an empty list. */
    fun bootstrapMedian(values: List<Double>, resamples: Int = 2000, seed: Long = 42): Interval? {
        if (values.isEmpty()) return null
        val med = median(values)!!
        if (values.size == 1) return Interval(med, med, med, 1)
        val rnd = Random(seed)
        val n = values.size
        val meds = DoubleArray(resamples)
        val buf = DoubleArray(n)
        for (r in 0 until resamples) {
            for (i in 0 until n) buf[i] = values[rnd.nextInt(n)]
            buf.sort()
            meds[r] = if (n % 2 == 1) buf[n / 2] else (buf[n / 2 - 1] + buf[n / 2]) / 2
        }
        meds.sort()
        val low = meds[(0.025 * (resamples - 1)).toInt()]
        val high = meds[(0.975 * (resamples - 1)).toInt()]
        return Interval(med, low, high, n)
    }

    /** Average ranks (ties share the mean rank). */
    fun ranks(values: List<Double>): List<Double> {
        val order = values.indices.sortedBy { values[it] }
        val out = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val r = (i + j) / 2.0 + 1
            for (k in i..j) out[order[k]] = r
            i = j + 1
        }
        return out.toList()
    }

    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 3) return null
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in x.indices) {
            sxy += (x[i] - mx) * (y[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
            syy += (y[i] - my) * (y[i] - my)
        }
        return if (sxx <= 0 || syy <= 0) null else sxy / sqrt(sxx * syy)
    }

    fun spearman(x: List<Double>, y: List<Double>): Double? = pearson(ranks(x), ranks(y))

    data class Permutation(val statistic: Double, val pValue: Double, val n: Int)

    /** Two-sided permutation test of the Spearman correlation: outcomes are shuffled [resamples] times. */
    fun spearmanPermutation(x: List<Double>, y: List<Double>, resamples: Int = 2000, seed: Long = 42): Permutation? {
        val observed = spearman(x, y) ?: return null
        val rnd = Random(seed)
        val ry = ranks(y).toMutableList()
        val rx = ranks(x)
        var extreme = 0
        for (r in 0 until resamples) {
            ry.shuffle(rnd)
            val c = pearson(rx, ry) ?: continue
            if (abs(c) >= abs(observed) - 1e-12) extreme++
        }
        return Permutation(observed, (extreme + 1.0) / (resamples + 1.0), x.size)
    }

    /** Rank of [value] among [history]: share of historical values that are at most [value], 0..1. */
    fun percentRank(history: List<Double>, value: Double): Double? =
        if (history.isEmpty()) null else history.count { it <= value }.toDouble() / history.size

    /** Least squares through the origin: slope of y on x. Null when x is all zero. */
    fun slopeThroughOrigin(x: List<Double>, y: List<Double>): Double? {
        var sxy = 0.0
        var sxx = 0.0
        for (i in x.indices) { sxy += x[i] * y[i]; sxx += x[i] * x[i] }
        return if (sxx <= 1e-12) null else sxy / sxx
    }

    /** Solves the normal equations of an ordinary least squares fit with an intercept. Null when singular. */
    fun ols(columns: List<List<Double>>, y: List<Double>): DoubleArray? {
        val n = y.size
        val k = columns.size + 1
        if (n < k + 1) return null
        val a = Array(k) { DoubleArray(k + 1) }
        for (r in 0 until n) {
            val row = DoubleArray(k) { if (it == 0) 1.0 else columns[it - 1][r] }
            for (i in 0 until k) {
                for (j in 0 until k) a[i][j] += row[i] * row[j]
                a[i][k] += row[i] * y[r]
            }
        }
        for (col in 0 until k) {
            var piv = col
            for (r in col + 1 until k) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-9) return null
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            for (r in 0 until k) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col..k) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(k) { a[it][k] / a[it][it] }
    }
}
