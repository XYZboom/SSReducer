package io.github.xyzboom.ssreducer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.OptionDelegate
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

@OptIn(ExperimentalAtomicApi::class)
abstract class CommonReducer(
    private val workingDir: String,
) : CliktCommand() {
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

    protected val predictTimeOut by run<OptionDelegate<Long>> {
        option("--predictTimeOut").long()
            .default(10000L)
            .validate { timeOut ->
                require(timeOut > 0) {
                    "Predict time out must be positive"
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

    protected val targetDir by lazy {
        if (resultDir != null) {
            File(resultDir!!)
        } else {
            createUniqueDirectory(File(workingDir, "ssreducer"))
        }
    }

    protected val saveTemps by run<OptionWithValues<Boolean, Boolean, Boolean>> {
        option("--saveTemps")
            .boolean()
            .default(false)
    }

    protected var predictTimes = AtomicInt(0)
    protected open val reducerName: String
        get() = this::class.simpleName!!

    // key: fileContents value: predict result --- cache hit times
    protected val fileContentsCache = ConcurrentHashMap<Map<String, String>, Pair<Boolean, Int>>()
    protected val appearedResult = ConcurrentHashMap<Map<String, String>, Unit>()

    protected open fun predict(fileContents: Map<String, String>): Boolean {
        val fileContentsCacheResult = fileContentsCache[fileContents]
        if (fileContentsCacheResult != null) {
            fileContentsCache[fileContents] = fileContentsCacheResult.first to fileContentsCacheResult.second + 1
            return fileContentsCacheResult.first
        }
        val tempDir: Path = Files.createTempDirectory(reducerName)
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
        val predictTimeNow = predictTimes.incrementAndFetch()
        println("predict $predictTimeNow at temp dir: $tempDir")
        val process = Runtime.getRuntime().exec(
            tempScript.absolutePath, System.getenv().map { "${it.key}=${it.value}" }.toTypedArray(),
            tempDir.toFile()
        )
        val predictExit = try {
            process.inputStream.bufferedReader().forEachLine {
                println(it)
            }
            process.errorStream.bufferedReader().forEachLine {
                System.err.println(it)
            }
            val completed = process.waitFor(predictTimeOut, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                -1
            } else {
                process.exitValue()
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            println("destroy $predictTimeNow")
            Thread.currentThread().interrupt()
        }
        val predictResult = predictExit == 0
        fileContentsCache[fileContents] = predictResult to 0
        if (predictResult) {
            println("$predictTimeNow is a successful predict")
        }
        if (saveTemps) {
            saveResult(fileContents, File(targetDir, "${predictTimeNow}_${predictResult}_${predictExit}"))
        }
        return predictResult
    }

    protected open fun saveResult(fileContents: Map<String, String>, targetDir: File) {
        for ((path, content) in fileContents) {
            val file = File(targetDir, File(path).name)
            file.parentFile.mkdirs()
            file.writeText(content)
        }
    }

    protected open fun saveResult(fileContents: Map<String, String>) {
        saveResult(fileContents, targetDir)
    }
}