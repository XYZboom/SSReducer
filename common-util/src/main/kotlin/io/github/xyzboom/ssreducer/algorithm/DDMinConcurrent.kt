package io.github.xyzboom.ssreducer.algorithm

import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class DDMinConcurrent<T>(
    private val threadCount: Int = Runtime.getRuntime().availableProcessors(),
    private val testFunc: (List<T>) -> Boolean
) {

    fun execute(input: List<T>): List<T> {
        if (input.isEmpty()) return input
        if (input.size == 1) {
            if (testFunc(emptyList())) return emptyList()
            return input
        }

        val executor = Executors.newFixedThreadPool(max(1, threadCount))
        try {
            return executeRecursive(input, 2, executor)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun executeRecursive(input: List<T>, n: Int, executor: ExecutorService): List<T> {
        if (input.isEmpty()) return input
        if (input.size == 1) {
            if (testFunc(emptyList())) return emptyList()
            return input
        }

        val parts = partition(input, n)

        if (n > 2) {
            val partIndex = firstPassingIndex(parts, executor, false) // parallel test on parts
            if (partIndex >= 0) {
                return executeRecursive(parts[partIndex], 2, executor)
            }
        }

        // test complements in parallel
        val complementIndex = firstPassingIndex(parts, executor, true)
        if (complementIndex >= 0) {
            val complement = getComplement(parts, complementIndex)
            return executeRecursive(complement, max(n - 1, 2), executor)
        }

        return if (n < input.size) {
            executeRecursive(input, min(n * 2, input.size), executor)
        } else {
            input
        }
    }

    private fun firstPassingIndex(parts: List<List<T>>, executor: ExecutorService, complement: Boolean): Int {
        if (parts.isEmpty()) return -1
        val completion = ExecutorCompletionService<Pair<Int, Boolean>>(executor)
        val futures = mutableListOf<java.util.concurrent.Future<Pair<Int, Boolean>>>()

        for (i in parts.indices) {
            val part = if (complement) {
                getComplement(parts, i)
            } else {
                parts[i]
            }
            val future = completion.submit {
                try {
                    Pair(i, testFunc(part))
                } catch (e: Exception) {
                    Pair(i, false)
                }
            }
            futures.add(future)
        }

        try {
            repeat(parts.size) {
                val completed = completion.take() // blocks until one completes
                val (idx, result) = completed.get()
                if (result) {
                    // cancel remaining
                    for (f in futures) {
                        if (!f.isDone) f.cancel(true)
                    }
                    return idx
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // ignore and treat as no passing part
        }
        return -1
    }
}
