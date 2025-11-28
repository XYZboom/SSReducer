package io.github.xyzboom.ssreducer

import java.io.File

val workingDir: String = File("").canonicalPath

fun createUniqueDirectory(dir: File): File {
    var counter = 1
    var result = dir
    val base = dir.parentFile
    val name = dir.name

    while (result.exists()) {
        result = File(base, "${name}_$counter")
        counter++
    }

    result.mkdirs()
    return result
}