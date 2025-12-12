package io.github.xyzboom.ssreducer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.OptionDelegate
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

abstract class CommonReducer(
    private val workingDir: String,
): CliktCommand() {
    protected val predictScript by run<OptionDelegate<File>> {
        option("--predict", "-p")
            .file(mustExist = true, canBeDir = false, canBeFile = true, mustBeReadable = true)
            .required()
            .validate { file ->
                require(file.absolutePath.startsWith(workingDir)) {
                    "Predict script must inside current working directory: $workingDir"
                }
            }
    }

    protected val resultDir by run<OptionDelegate<String?>> {
        option("-o", "--out").validate { path ->
            val file = File(path)
            if (file.exists()) {
                require(file.isDirectory) {
                    "The output must be a directory"
                }
            }
        }
    }

    protected val sourceRoots by run<OptionDelegate<List<File>>> {
        option("--sourceRoots")
            .file(mustExist = true, canBeDir = true, canBeFile = true, mustBeReadable = true)
            .multiple(default = emptyList())
            .validate { files ->
                require(files.all { file ->
                    file.absolutePath.startsWith(workingDir)
                }) {
                    "Source roots must inside current working directory: $workingDir"
                }
            }
    }

    protected var predictTimes = 0
    protected open val reducerName: String
        get() = this::class.simpleName!!

    // key: fileContents value: predict result --- cache hit times
    protected val fileContentsCache = mutableMapOf<Map<String, String>, Pair<Boolean, Int>>()
    protected val appearedResult = mutableSetOf<Map<String, String>>()

    protected open fun predict(fileContents: Map<String, String>): Boolean {
        val fileContentsCacheResult = fileContentsCache[fileContents]
        if (fileContentsCacheResult != null) {
            fileContentsCache[fileContents] = fileContentsCacheResult.first to fileContentsCacheResult.second + 1
            return fileContentsCacheResult.first
        }
        val tempDir: Path = Files.createTempDirectory(reducerName)
        println("predict at temp dir: $tempDir")
        tempDir.toFile().deleteOnExit()
        for ((path, content) in fileContents) {
            val file = tempDir.resolve(path.removePrefix(workingDir).removePrefix("/")).toFile()
            file.parentFile.mkdirs()
            file.writeText(content)
        }
        val scriptRelativePath = predictScript.absolutePath.removePrefix(workingDir).removePrefix("/")
        val tempScript = tempDir.resolve(scriptRelativePath).toFile()
        tempScript.deleteOnExit()
        predictScript.copyTo(tempScript)
        tempScript.setExecutable(true)
        tempScript.setReadable(true)
        val process = Runtime.getRuntime().exec(
            tempScript.absolutePath, System.getenv().map { "${it.key}=${it.value}" }.toTypedArray(),
            tempDir.toFile()
        )
        process.inputStream.bufferedReader().forEachLine {
            println(it)
        }
        process.errorStream.bufferedReader().forEachLine {
            System.err.println(it)
        }
        val predictExit = process.waitFor()
        predictTimes++
        val predictResult = predictExit == 0
        fileContentsCache[fileContents] = predictResult to 0
        return predictResult
    }

    protected open fun saveResult(fileContents: Map<String, String>) {
        val resultDir = if (resultDir != null) {
            File(resultDir!!)
        } else {
            createUniqueDirectory(File(workingDir, "ssreducer"))
        }
        for ((path, content) in fileContents) {
            val file = File(resultDir, File(path).name)
            file.parentFile.mkdirs()
            file.writeText(content)
        }
    }
}