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
        var currentContents = currentGroup.fileContents()
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
                    val remainElements = it.associateWith { currentLevel } + notCurrentElements
                    val group = currentGroup.copyOf(remainElements)
                    group.reconstructDependencies()
                    val fileContents = group.fileContents()
                    val predictResult = predict(fileContents)
                    if (predictResult) {
                        // todo: CLion does not support reference resolve on copied elements now
                        currentContents = fileContents
                        currentGroup = currentGroup.copyOf(remainElements)
                    }
                    return@DDMin predictResult
                }
                ddmin.execute(currentElements)
                currentLevel++
            }
            if (currentContents in appearedResult) {
                saveResult(currentContents)
                break
            }
            appearedResult.add(currentContents)
        }

        println("predict times: $predictTimes")
        println("cache hit times: ${fileContentsCache.values.sumOf { it.second }}")
    }
}