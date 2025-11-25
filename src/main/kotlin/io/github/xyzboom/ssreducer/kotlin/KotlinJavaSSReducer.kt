package io.github.xyzboom.ssreducer.kotlin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import io.github.xyzboom.ssreducer.IReducer
import io.github.xyzboom.ssreducer.KaSessionRunner
import io.github.xyzboom.ssreducer.algorithm.DDMin
import io.github.xyzboom.ssreducer.workingDir
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class KotlinJavaSSReducer : CliktCommand(), IReducer {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            KotlinJavaSSReducer().main(args)
        }
    }

    private val predictScript by run<OptionDelegate<File>> {
        option("--predict", "-p")
            .file()
            .required()
            .validate { file ->
                require(file.canonicalPath.startsWith(workingDir)) {
                    "Predict script must inside current working directory: $workingDir"
                }
            }
    }

    private val jvmTarget by run<OptionWithValues<JvmTarget, JvmTarget, JvmTarget>> {
        option("--jvmTarget", "-jt")
            .enum<JvmTarget> { it.description }
            .default(JvmTarget.DEFAULT)
    }
    private val languageVersion by run<OptionWithValues<LanguageVersion, LanguageVersion, LanguageVersion>> {
        option("--languageVersion", "-lv")
            .enum<LanguageVersion> { it.versionString }
            .default(LanguageVersion.FIRST_NON_DEPRECATED)
    }
    private val apiVersion by run<OptionWithValues<LanguageVersion, LanguageVersion, LanguageVersion>> {
        option("--apiVersion", "-av")
            .enum<LanguageVersion> { it.versionString }
            .default(LanguageVersion.FIRST_NON_DEPRECATED)
    }
    private val jdkHome by run<OptionWithValues<File?, File, File>> {
        option("--jdkHome")
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
    }
    private val moduleName by run<OptionWithValues<String, String, String>> {
        option("--moduleName")
            .default("<mock-module-name>")
    }
    private val sourceRoots by run<OptionDelegate<List<File>>> {
        option("--sourceRoots")
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
            .multiple(default = emptyList())
            .validate { files ->
                require(files.all { file ->
                    file.canonicalPath.startsWith(workingDir)
                }) {
                    "Source roots must inside current working directory: $workingDir"
                }
            }
    }
    private val libraries by run<OptionWithValues<List<File>, File, File>> {
        option("--libraries", "-l")
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
            .multiple(default = emptyList())
    }
    private val friends by run<OptionWithValues<List<File>, File, File>> {
        option("--friends", "-f")
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
            .multiple(default = emptyList())
    }

    private fun predict(fileContents: Map<String, String>): Boolean {
        val tempDir: Path = Files.createTempDirectory("kjSSReducer")
        tempDir.toFile().deleteOnExit()
        for ((path, content) in fileContents) {
            val file = tempDir.resolve(path.removePrefix(workingDir).removePrefix("/")).toFile()
            file.parentFile.mkdirs()
            file.writeText(content)
        }
        val scriptRelativePath = predictScript.canonicalPath.removePrefix(workingDir).removePrefix("/")
        val tempScript = tempDir.resolve(scriptRelativePath).toFile()
        tempScript.deleteOnExit()
        predictScript.copyTo(tempScript)
        tempScript.setExecutable(true)
        tempScript.setReadable(true)
        val process = Runtime.getRuntime().exec(
            tempScript.canonicalPath, System.getenv().map { "${it.key}=${it.value}" }.toTypedArray(),
            tempDir.toFile()
        )
        process.inputStream.bufferedReader().forEachLine {
            println(it)
        }
        process.errorStream.bufferedReader().forEachLine {
            System.err.println(it)
        }
        val predictResult = process.waitFor()
        return predictResult == 0
    }

    @OptIn(KaExperimentalApi::class)
    override fun run() {
        val runner = KaSessionRunner(
            jvmTarget,
            languageVersion,
            apiVersion,
            jdkHome,
            moduleName,
            sourceRoots,
            libraries,
            friends,
        )
        runner.runInSession { session, environment, modules ->
            val project = session.project
            val module = modules[0] as KaSourceModule
            val psiRoots = module.psiRoots.filterIsInstance<PsiFile>()
            val copyMap = psiRoots.associateWith { it.copy() as PsiFile }
            val newRoots = copyMap.values
            val groupedElements = GroupedElements.groupElements(project, newRoots, copyMap)

            fun reduceFiles(initGroup: GroupedElements): GroupedElements {
                val files = initGroup.files.toList()
                val ddmin = DDMin {
                    val newGroup = initGroup.copy(files = it.toSet())
                    newGroup.reconstructDependencies()
                    return@DDMin predict(newGroup.fileContents())
                }
                val remain = ddmin.execute(files)
                return initGroup.copy(files = remain.toSet())
            }

            groupedElements.reconstructDependencies()

            reduceFiles(groupedElements)
            for (psiRoot in newRoots) {
                if (psiRoot is PsiJavaFile) {
                    println()
                } else if (psiRoot is KtFile) {
                    println()
                }
            }
        }
    }

    override fun doReduce(args: Array<String>) {
        main(args)
    }
}