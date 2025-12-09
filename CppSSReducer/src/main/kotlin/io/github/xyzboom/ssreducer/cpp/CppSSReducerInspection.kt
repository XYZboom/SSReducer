package io.github.xyzboom.ssreducer.cpp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import kotlin.system.exitProcess
import com.github.ajalt.clikt.command.parse
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parsers.CommandLineParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager


class CppSSReducerInspection : GlobalInspectionTool() {
    companion object {
        val logger = Logger.getInstance(CppSSReducerInspection::class.java)
    }

    override fun runInspection(
        scope: AnalysisScope,
        manager: InspectionManager,
        globalContext: GlobalInspectionContext,
        problemDescriptionsProcessor: ProblemDescriptionsProcessor
    ) {
        val project = manager.project
        val args = System.getProperty("CppSSReducerExtraArgs") ?: run {
            System.err.println(
                "No arguments specified! " +
                        "Please Specify arguments using -DCppSSReducerExtraArgs=\"...\""
            )
            exitProcess(1)
        }
        val cmd = CppSSReducerCmd(project.basePath!!)
        try {
            CommandLineParser.parseAndRun(
                cmd,
                args.split(" ").filter(String::isNotEmpty)
            ) { cmd ->
                cmd as CppSSReducerCmd
                val localFileSystem = LocalFileSystem.getInstance()
                val vFiles = collectVirtualFilesByRoots(localFileSystem, cmd.sourceRoots)
                val psiManager = PsiManager.getInstance(project)
                val ocFiles = vFiles.mapNotNull { psiManager.findFile(it) }
                println(ocFiles)
            }
        } catch (e: CliktError) {
            cmd.echoFormattedHelp(e)
            cmd.currentContext.exitProcess(e.statusCode)
        }
        println()
    }
}