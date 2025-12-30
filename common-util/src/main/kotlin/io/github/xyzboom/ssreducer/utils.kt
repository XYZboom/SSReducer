package io.github.xyzboom.ssreducer

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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

fun collectSourceFilePaths(root: Path, predicate: (Path) -> Boolean): List<Path> {
    val result = mutableListOf<Path>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                return if (Files.isReadable(dir))
                    FileVisitResult.CONTINUE
                else
                    FileVisitResult.SKIP_SUBTREE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!Files.isRegularFile(file) || !Files.isReadable(file))
                    return FileVisitResult.CONTINUE
                if (predicate(file)) {
                    result.add(file)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        }
    )
    return result
}

fun (() -> Unit)?.andThen(other: (() -> Unit)?): (() -> Unit)? {
    if (this == null && other == null) {
        return null
    }
    return {
        this?.invoke()
        other?.invoke()
    }
}
