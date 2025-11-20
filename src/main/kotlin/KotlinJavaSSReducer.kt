package io.github.xyzboom.ssreducer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

class KotlinJavaSSReducer : CliktCommand(), IReducer {
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
    private val jdkHome by run<OptionWithValues<File?, File, File>> {
        option("--jdkHome")
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
    }
    private val moduleName by run<OptionWithValues<String, String, String>> {
        option("--moduleName")
            .default("<mock-module-name>")
    }
    private val sourceRoots by run<OptionWithValues<List<File>, File, File>> {
        option("--sourceRoots")
            .file(mustExist = true, canBeDir = true, canBeFile = false, mustBeReadable = true)
            .multiple(default = emptyList())
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
            val psiRoots = module.psiRoots
            val newRoots = psiRoots.map { it.copy() }
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