package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.jetbrains.cidr.lang.psi.OCFile
import io.github.xyzboom.ssreducer.CommonReducer
import io.github.xyzboom.ssreducer.algorithm.DDMin

class CppSSReducer(
    workingDir: String,
    val project: Project,
) : CommonReducer(workingDir) {
    override fun run() {
        doReduce()
    }

    private fun doReduce() {
        val localFileSystem = LocalFileSystem.getInstance()
        val vFiles = collectVirtualFilesByRoots(localFileSystem, sourceRoots)
        val psiManager = PsiManager.getInstance(project)
        val ocFiles = vFiles.mapNotNull { psiManager.findFile(it) }.filterIsInstance<OCFile>()
        GroupElements.groupElements(project, ocFiles)
        val copiedRoots = ocFiles.map { it.copy() as OCFile }
        var currentGroup = GroupElements.groupElements(project, copiedRoots)
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
                    // todo: CLion does not support reference resolve on copied elements now
                    currentGroup = currentGroup.copyOf(it.associateWith { currentLevel } + notCurrentElements)
//                    currentGroup = group.applyEdit()
                }
                return@DDMin predictResult
            }
            ddmin.execute(currentElements)
            currentLevel++
        }
//        saveResult(currentGroup.fileContents())

//        println("predict times: $predictTimes")
    }
}