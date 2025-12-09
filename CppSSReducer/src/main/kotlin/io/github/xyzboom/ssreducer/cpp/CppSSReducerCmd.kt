package io.github.xyzboom.ssreducer.cpp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.OptionDelegate
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.file
import java.io.File

class CppSSReducerCmd(
    private val workingDir: String
) : CliktCommand() {
    val sourceRoots by run<OptionDelegate<List<File>>> {
        option("--sourceRoots")
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
            .multiple(default = emptyList())
            .validate { files ->
                require(files.all { file ->
                    file.absolutePath.startsWith(workingDir)
                }) {
                    "Source roots must inside current working directory: $workingDir"
                }
            }
    }

    val resultDir by run<OptionDelegate<String?>> {
        option("-o", "--out").validate { path ->
            val file = File(path)
            if (file.exists()) {
                require(file.isDirectory) {
                    "The output must be a directory"
                }
            }
        }
    }

    private val predictScript by run<OptionDelegate<File>> {
        option("--predict", "-p")
            .file()
            .required()
            .validate { file ->
                require(file.absolutePath.startsWith(workingDir)) {
                    "Predict script must inside current working directory: $workingDir"
                }
            }
    }

    override fun run() {
        throw IllegalArgumentException("Should not be called!")
    }
}