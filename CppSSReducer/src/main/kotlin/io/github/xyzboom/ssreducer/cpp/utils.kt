package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.xyzboom.ssreducer.collectSourceFilePaths
import java.io.File
import kotlin.io.path.extension

fun collectVirtualFilesByVirtualRoots(
    localFileSystem: LocalFileSystem,
    sourceRoots: Iterable<VirtualFile>
): List<VirtualFile> {
    return collectVirtualFilesByRoots(localFileSystem, sourceRoots.map { it.toNioPath().toFile() })
}

fun collectVirtualFilesByRoots(localFileSystem: LocalFileSystem, sourceRoots: Iterable<File>): List<VirtualFile> {
    return buildList {
        for (root in sourceRoots) {
            when {
                root.isDirectory -> {
                    val paths = collectSourceFilePaths(root.toPath()) { it.extension in cppExtensions }
                    for (path in paths) {
                        val virtualFile = localFileSystem.findFileByNioFile(path.toAbsolutePath()) ?: continue
                        add(virtualFile)
                    }
                }

                root.extension in cppExtensions -> localFileSystem.findFileByNioFile(root.toPath())?.let { add(it) }
            }
        }
        sortBy { it.path }
    }
}
