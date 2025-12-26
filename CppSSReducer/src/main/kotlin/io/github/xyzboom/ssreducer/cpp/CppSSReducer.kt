package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.jetbrains.cidr.lang.psi.OCFile
import io.github.xyzboom.ssreducer.CommonReducer
import io.github.xyzboom.ssreducer.PsiWrapper
import io.github.xyzboom.ssreducer.algorithm.DDMinConcurrent
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class CppSSReducer(
    workingDir: String,
    val project: Project,
) : CommonReducer(workingDir) {
    override fun run() {
        doReduce()
    }

    class Ref<T>(var value: T)

    private val elementsCache = ConcurrentHashMap<Set<Long?>, Ref<Pair<Boolean, Int>>>()

    private fun myPredict(elements: Set<Long?>, fileContents: Map<String, String>): Boolean {
        val result = predict(fileContents)
        elementsCache[elements] = Ref(result to 0)
        return result
    }

    private fun elementsCacheResult(elements: Set<Long?>): Boolean? {
        val cacheResult = elementsCache[elements]
        if (cacheResult != null) {
            val result = cacheResult.value.first
            cacheResult.value = cacheResult.value.first to cacheResult.value.second + 1
            return result
        }
        return null
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
                val ddmin = DDMinConcurrent DDMin@ {
                    val notCurrentElements = currentGroup.elements.filter { ele -> ele.value != currentLevel }
                    val remainElements = (it + typedefs).associateWith { currentLevel } + notCurrentElements
                    val group = ReadAction.nonBlocking(Callable {
                        currentGroup.copyOf(remainElements)
                    }).executeSynchronously()
                    val (needEdit, needDelete) =
                        ReadAction.nonBlocking(Callable {
                            return@Callable group.preReconstructDependencies()
                        }).executeSynchronously()
                    val elementIds = group.elements.keys.map { ele -> ele.id }.toSet()
                    val cacheResult = elementsCacheResult(elementIds)
                    if (cacheResult != null) {
                        return@DDMin cacheResult
                    }
                    val fileContents = ReadAction.nonBlocking(Callable {
                        group.reconstructDependencies(needEdit, needDelete)
                        group.fileContents()
                    }).executeSynchronously()
                    val predictResult = myPredict(elementIds, fileContents)
                    if (predictResult) {
                        currentContents = fileContents
                        currentGroup = group.applyEdit()
                    }
                    return@DDMin predictResult
                }
                ddmin.execute(currentNonTypedefs)
                currentLevel++
            }
            if (appearedResult.containsKey(currentContents)) {
                saveResult(currentContents)
                break
            }
            appearedResult[currentContents] = Unit
        }

        println("predict times: ${predictTimes.load()}")
        println("file cache hit times: ${fileContentsCache.values.sumOf { it.second }}")
        println("elements cache hit times: ${elementsCache.values.sumOf { it.value.second }}")
    }
}