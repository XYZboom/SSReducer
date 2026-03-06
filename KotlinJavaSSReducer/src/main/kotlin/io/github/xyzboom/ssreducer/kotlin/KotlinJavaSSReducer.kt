package io.github.xyzboom.ssreducer.kotlin

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.intellij.application.options.CodeStyle
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.JavaCodeStyleSettings
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.xyzboom.ssreducer.*
import io.github.xyzboom.ssreducer.algorithm.DDMin
import io.github.xyzboom.ssreducer.algorithm.DDMinConcurrent
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.io.File
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.measureTime

@OptIn(ExperimentalAtomicApi::class)
class KotlinJavaSSReducer : CommonReducer(workingDir), IReducer {
    companion object {
        private val logger = KotlinLogging.logger {}
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

    private val startMark = TimeSource.Monotonic.markNow()
    private val profiler = mutableListOf<Pair<Duration, Map<String, String>>>()

    private fun profile(fileContents: Map<String, String>) {
        val elapsed = startMark.elapsedNow()
        profiler.add(elapsed to fileContents)
    }

    @OptIn(KaExperimentalApi::class)
    private fun myDoReduce() {
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
            val settings = createTempSettings(project)
            val module = modules[0] as KaSourceModule
            val psiRoots = module.psiRoots.filterIsInstance<PsiFile>()
            GroupElements.groupElements(project, psiRoots)
            val copiedRoots = psiRoots.map { it.copy() as PsiFile }
            var currentGroup = GroupElements.groupElements(project, copiedRoots)
            var currentFileContents = currentGroup.fileContents()
            var fixPoint = 0
            while (true) {
                logger.info { "=== Reduce Round: ${fixPoint++} ===" }
                var currentLevel = 1
                while (currentLevel <= currentGroup.maxLevel) {
                    val currentElements = currentGroup.elements.filter { it.value == currentLevel }.keys.toList()
                    if (currentElements.isEmpty()) {
                        currentLevel++
                        continue
                    }
                    val notCurrentElements = currentGroup.elements.filter { it.value != currentLevel }
                    val predict: (List<PsiWrapper<*>>) -> Pair<Boolean, Pair<GroupElements, Map<String, String>>> =
                        DDMin@{
                            val group = currentGroup.copyOf(it.associateWith { currentLevel } + notCurrentElements)
                            CodeStyle.runWithLocalSettings(project, settings, Runnable {
                                val rdCount = group.reconstructDependencies(rdProb, Random(seed))
                                reconstructedCount.fetchAndAdd(rdCount)
                            })
                            val fileContents = group.fileContents()
                            val predictResult = predict(fileContents.asSavable())
                            return@DDMin predictResult to (group to fileContents)
                        }
                    val onSuccess: (List<PsiWrapper<*>>, Pair<GroupElements, Map<String, String>>) -> Unit =
                        onSuccess@{ _, (remainGroup, fileContents) ->
                            currentGroup = remainGroup.applyEdit()
                            currentFileContents = fileContents
                            profile(fileContents)
                        }
                    val ddmin = if (jobs == 1) {
                        DDMin(predict, onSuccess)
                    } else {
                        DDMinConcurrent(jobs, predict, onSuccess)
                    }
                    ddmin.execute(currentElements)
                    currentLevel++
                }
                val currentSavable = currentFileContents.asSavable()
                if (appearedResult.containsKey(currentSavable)) {
                    saveResult(currentSavable)
                    break
                }
                appearedResult[currentSavable] = Unit
            }

            saveProfiler(project)
            println("predict times: ${predictTimes.load() - canceledPredictTimes.load()}")
            println("reconstructed times: ${reconstructedCount.load()}")
            println("predict canceled times: ${canceledPredictTimes.load()}")
            println("file cache hit times: ${fileContentsCache.values.sumOf { it.second }}")
        }
    }

    private fun createTempSettings(project: Project): CodeStyleSettings {
        val base: CodeStyleSettings = CodeStyle.getSettings(project)
        val manager: CodeStyleSettingsManager = CodeStyleSettingsManager.getInstance()
        val tmp = manager.cloneSettings(base)

        val java = tmp.getCustomSettings(JavaCodeStyleSettings::class.java)
        java.CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND = 1
        java.NAMES_COUNT_TO_USE_IMPORT_ON_DEMAND = 1
        return tmp
    }

    private fun saveProfiler(project: Project) {
        targetDir.mkdirs()
        val csv = CSVPrinter(
            targetDir.resolve("profiler.csv").writer(),
            CSVFormat.Builder.create()
                .setHeader("Time(s)", "File", "Token Count")
                .get()
        )
        for ((dur, files) in profiler) {
            for ((file, content) in files) {
                val language = if (file.endsWith(".java")) {
                    JavaLanguage.INSTANCE
                } else {
                    KotlinLanguage.INSTANCE
                }
                val tokens = countTokens(content, language, project)
                csv.printRecord(dur.inWholeSeconds, file.removePrefix(workingDir), tokens)
            }
        }
        csv.close(true)
    }

    override fun run() {
        val times = measureTime {
            myDoReduce()
        }
        println("reduce time: $times")
    }

    override val reducerName: String
        get() = super<IReducer>.reducerName

    override fun doReduce(args: Array<String>) {
        main(args)
    }
}