package io.github.xyzboom.ssreducer.algorithm

import kotlin.math.max
import kotlin.math.min

class DDMin<T>(private val testFunc: (List<T>) -> Boolean) {

    fun execute(input: List<T>): List<T> {
        if (input.isEmpty()) return input
        if (input.size == 1) {
            if (testFunc(emptyList())) return emptyList()
            return input
        }

        return executeRecursive(input, 2)
    }

    private fun executeRecursive(
        input: List<T>,
        n: Int
    ): List<T> {
        if (input.isEmpty()) return input
        if (input.size == 1) {
            if (testFunc(emptyList())) return emptyList()
            return input
        }
        // split input into n parts
        val parts = partition(input, n)

        if (n > 2) {
            for (part in parts) {
                if (testFunc(part)) {
                    return executeRecursive(part, 2)
                }
            }
        }

        // test on complement of each part
        for (i in parts.indices) {
            val complement = getComplement(parts, i)
            // if complement passes test, run on complement
            if (testFunc(complement)) {
                return executeRecursive(complement, max(n - 1, 2))
            }
        }

        // if we can split further, do it. Otherwise, return the result.
        return if (n < input.size) {
            executeRecursive(input, min(n * 2, input.size))
        } else {
            input
        }
    }

}
