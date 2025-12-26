package io.github.xyzboom.ssreducer.algorithm

/**
 * split list into n parts
 */
internal fun <T> partition(list: List<T>, n: Int): List<List<T>> {
    val result = mutableListOf<List<T>>()
    val chunkSize = list.size / n
    var remainder = list.size % n

    var start = 0
    for (i in 0 until n) {
        val currentChunkSize = chunkSize + if (remainder > 0) 1 else 0
        remainder--

        if (start < list.size) {
            val end = minOf(start + currentChunkSize, list.size)
            result.add(list.subList(start, end))
            start = end
        }
    }

    return result
}

internal fun <T> getComplement(parts: List<List<T>>, excludeIndex: Int): List<T> {
    val result = mutableListOf<T>()
    for (i in parts.indices) {
        if (i != excludeIndex) {
            result.addAll(parts[i])
        }
    }
    return result
}