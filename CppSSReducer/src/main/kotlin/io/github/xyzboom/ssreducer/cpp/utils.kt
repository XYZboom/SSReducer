package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.xyzboom.ssreducer.collectSourceFilePaths
import java.io.File
import java.util.logging.*
import kotlin.io.path.extension

fun setupLogger() {
    val logger = Logger.getLogger("io.github.xyzboom.ssreducer")
    val consoleHandler = ConsoleHandler()
    consoleHandler.level = Level.WARNING
    val traceHandler = object : StreamHandler() {
        init {
            setOutputStream(System.out)
        }
        override fun publish(record: LogRecord?) {
            super.publish(record)
            flush()
        }

        override fun close() {
            flush()
        }
    }
    val formatter = Log4jStyleFormatter()
    consoleHandler.setFormatter(formatter)
    traceHandler.setFormatter(formatter)
    logger.addHandler(consoleHandler)
    logger.addHandler(traceHandler)
}


class Log4jStyleFormatter : Formatter() {
    override fun format(record: LogRecord): String {
        val sb = StringBuilder()

        sb.append("[")
            .append(Thread.currentThread().name)
            .append("] ")

        val levelName = record.level.name
        sb.append(String.format("%-5s ", levelName))

        if (record.getSourceClassName() != null) {
            sb.append(record.getSourceClassName())
            if (record.getSourceMethodName() != null) {
                sb.append(".").append(record.getSourceMethodName())
            }
            sb.append(" ")
        }

        sb.append("- ").append(formatMessage(record))

        if (record.thrown != null) {
            sb.append(LINE_SEPARATOR)
            val thrown = record.thrown
            sb.append(thrown.toString()).append(LINE_SEPARATOR)
            for (element in thrown.stackTrace) {
                sb.append("\tat ").append(element).append(LINE_SEPARATOR)
            }
        }

        sb.append(LINE_SEPARATOR)
        return sb.toString()
    }

    companion object {
        private val LINE_SEPARATOR: String? = System.lineSeparator()
    }
}

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
