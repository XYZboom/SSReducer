package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.jetbrains.cidr.lang.psi.OCFile
import io.github.xyzboom.ssreducer.CommonReducer
import io.github.xyzboom.ssreducer.PsiWrapper
import io.github.xyzboom.ssreducer.algorithm.DDMin

class CppSSReducer(
    workingDir: String,
    val project: Project,
) : CommonReducer(workingDir) {
    override fun run() {
        doReduce()
    }

    class Ref<T>(var value: T)

    private val elementsCache = mutableMapOf<Set<PsiWrapper<*>>, Ref<Pair<Boolean, Int>>>()

    private fun myPredict(group: GroupElements, fileContents: Map<String, String>): Boolean {
        val elements = group.elements.keys
        val cacheResult = elementsCache[elements]
        if (cacheResult != null) {
            val result = cacheResult.value.first
            cacheResult.value = cacheResult.value.first to cacheResult.value.second + 1
            return result
        }
        return predict(fileContents)
    }

    private fun doReduce() {
        val localFileSystem = LocalFileSystem.getInstance()
        val vFiles = collectVirtualFilesByRoots(localFileSystem, sourceRoots)
        val psiManager = PsiManager.getInstance(project)
        val ocFiles = vFiles.mapNotNull { psiManager.findFile(it) }.filterIsInstance<OCFile>()
        GroupElements.preprocess(project, ocFiles)
        val copiedRoots = ocFiles.map { it.copy() as OCFile }
        var currentGroup = GroupElements.groupElements(project, copiedRoots)
        var currentContents = currentGroup.fileContents()
        while (true) {
            var currentLevel = 1
            while (currentLevel <= currentGroup.maxLevel) {
                val currentElements = currentGroup.elements.filter { it.value == currentLevel }.keys.toList()
                val (typedefs, currentNonTypedefs) =
                    if (appearedResult.isEmpty()) {
                        currentElements.partition {
                            GroupElements.isTypedef(it.element)
                        }
                    } else {
                        emptyList<PsiWrapper<*>>() to currentElements
                    }
                if (currentElements.isEmpty()) {
                    currentLevel++
                    continue
                }
                val notCurrentElements = currentGroup.elements.filter { it.value != currentLevel }
                val ddmin = DDMin {
                    val remainElements = (it + typedefs).associateWith { currentLevel } + notCurrentElements
                    val group = currentGroup.copyOf(remainElements)
                    group.reconstructDependencies()
                    val fileContents = group.fileContents()
                    val predictResult = myPredict(group, fileContents)
                    if (predictResult) {
                        currentContents = fileContents
                        currentGroup = group.applyEdit()
                    }
                    return@DDMin predictResult
                }
                ddmin.execute(currentNonTypedefs)
                currentLevel++
            }
            if (currentContents in appearedResult) {
                saveResult(currentContents)
                break
            }
            appearedResult.add(currentContents)
        }

        println("predict times: $predictTimes")
        val cacheHitTimes = fileContentsCache.values.sumOf { it.second } + elementsCache.values.sumOf { it.value.second }
        println("cache hit times: $cacheHitTimes")
    }
}