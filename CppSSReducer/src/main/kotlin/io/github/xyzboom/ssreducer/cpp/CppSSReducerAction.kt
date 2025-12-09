package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.jetbrains.cidr.lang.psi.OCFile

class CppSSReducerAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: throw IllegalArgumentException("No project")
        val files = FileChooser.chooseFiles(
            FileChooserDescriptor(
                true, true, false,
                false, false, true
            ),
            project,
            project.projectFile
        )
        val cppFiles = collectVirtualFilesByVirtualRoots(LocalFileSystem.getInstance(), files.asIterable())
        val psiManager = PsiManager.getInstance(project)
        val ocFiles = cppFiles.mapNotNull { psiManager.findFile(it) }.filterIsInstance<OCFile>()
        println(ocFiles)
    }

}