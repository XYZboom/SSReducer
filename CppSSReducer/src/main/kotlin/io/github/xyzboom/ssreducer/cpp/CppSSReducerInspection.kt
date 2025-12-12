package io.github.xyzboom.ssreducer.cpp

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import kotlin.system.exitProcess
import com.intellij.openapi.diagnostic.Logger
import com.github.ajalt.clikt.core.main
import com.intellij.openapi.project.DumbService

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
        val exception = runCatching {
            val project = manager.project
            val args = System.getProperty("CppSSReducerExtraArgs") ?: run {
                System.err.println(
                    "No arguments specified! " +
                            "Please Specify arguments using -DCppSSReducerExtraArgs=\"...\""
                )
                exitProcess(1)
            }
            DumbService.getInstance(project).smartInvokeLater {
                CppSSReducer(project.basePath!!, project).main(args.split(" ").filter(String::isNotEmpty))
                exitProcess(0)
            }
        }.exceptionOrNull()
        if (exception != null) {
            exception.printStackTrace()
            exitProcess(1)
        }
    }
}