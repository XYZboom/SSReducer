package io.github.xyzboom.ssreducer.kotlin

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.intellij.psi.PsiFile
import io.github.xyzboom.ssreducer.CommonReducer
import io.github.xyzboom.ssreducer.IReducer
import io.github.xyzboom.ssreducer.algorithm.DDMin
import io.github.xyzboom.ssreducer.workingDir
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import java.io.File
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class KotlinJavaSSReducer : CommonReducer(workingDir), IReducer {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            KotlinJavaSSReducer().main(args)
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
    private val jdkHome by run<OptionWithValues<File, File, File>> {
        option("--jdkHome")
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
            .default(File(System.getProperty("java.home")!!))
    }
    private val moduleName by run<OptionWithValues<String, String, String>> {
        option("--moduleName")
            .default("<mock-module-name>")
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
            GroupElements.groupElements(project, psiRoots)
            val copiedRoots = psiRoots.map { it.copy() as PsiFile }
            var currentGroup = GroupElements.groupElements(project, copiedRoots)
            while (true) {
                var currentLevel = 1
                while (currentLevel <= currentGroup.maxLevel) {
                    val currentElements = currentGroup.elements.filter { it.value == currentLevel }.keys.toList()
                    if (currentElements.isEmpty()) {
                        currentLevel++
                        continue
                    }
                    val notCurrentElements = currentGroup.elements.filter { it.value != currentLevel }
                    val ddmin = DDMin {
                        val group = currentGroup.copyOf(it.associateWith { currentLevel } + notCurrentElements)
                        group.reconstructDependencies()
                        val fileContents = group.fileContents()
                        val predictResult = predict(fileContents)
                        if (predictResult) {
                            currentGroup = group.applyEdit()
                        }
                        return@DDMin predictResult
                    }
                    ddmin.execute(currentElements)
                    currentLevel++
                }
                val fileContents = currentGroup.fileContents()
                if (appearedResult.containsKey(fileContents)) {
                    saveResult(currentGroup.fileContents())
                    break
                }
                appearedResult[fileContents] = Unit
            }
            saveResult(currentGroup.fileContents())

            println("predict times: $predictTimes")
            println("cache hit times: ${fileContentsCache.values.sumOf { it.second }}")
        }
    }

    override val reducerName: String
        get() = super<IReducer>.reducerName

    override fun doReduce(args: Array<String>) {
        main(args)
    }
}