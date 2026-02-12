package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.jetbrains.cidr.lang.OCLanguage
import com.jetbrains.cidr.lang.psi.OCFile
import io.github.xyzboom.ssreducer.CommonReducer
import io.github.xyzboom.ssreducer.PsiWrapper
import io.github.xyzboom.ssreducer.algorithm.DDMin
import io.github.xyzboom.ssreducer.algorithm.DDMinConcurrent
import io.github.xyzboom.ssreducer.countTokens
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

@OptIn(ExperimentalAtomicApi::class)
class CppSSReducer(
    workingDir: String,
    val project: Project,
) : CommonReducer(workingDir) {
    private val startMark = TimeSource.Monotonic.markNow()
    override fun run() {
        doReduce()
    }

    class Ref<T>(var value: T)

    private val elementsCache = ConcurrentHashMap<Set<Long?>, Ref<Pair<Boolean, Int>>>()
    private val profiler = mutableListOf<Pair<Duration, Map<String, String>>>()

    private fun profile(fileContents: Map<String, String>) {
        val elapsed = startMark.elapsedNow()
        profiler.add(elapsed to fileContents)
    }

    private fun myPredict(elements: Set<Long?>, fileContents: Map<String, String>): Boolean {
        val result = predict(fileContents.asSavable())
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
        profile(currentContents)
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
                val predict: (List<PsiWrapper<*>>) -> Pair<Boolean, Pair<Map<String, String>, GroupElements>?> = DDMin@{
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
                        return@DDMin cacheResult to null
                    }
                    val fileContents = ReadAction.nonBlocking(Callable {
                        group.reconstructDependencies(
                            needEdit, needDelete,
                            rdProb, Random(seed)
                        )
                        group.fileContents()
                    }).executeSynchronously()
                    val predictResult = myPredict(elementIds, fileContents)
                    return@DDMin predictResult to (fileContents to group)
                }
                val onSuccess: (List<PsiWrapper<*>>, Pair<Map<String, String>, GroupElements>?) -> Unit =
                    onSuccess@{ _, data ->
                        // cached result, no need to change
                        val (fileContents, group) = data ?: return@onSuccess
                        currentContents = fileContents
                        currentGroup = group.applyEdit()
                        profile(fileContents)
                    }
                val ddmin = if (jobs == 1) {
                    DDMin(predict, onSuccess)
                } else {
                    DDMinConcurrent(jobs, predict, onSuccess)
                }
                ddmin.execute(currentNonTypedefs.sortedBy { it.element.textLength })
                currentLevel++
            }
            val currentSavable = currentContents.asSavable()
            if (appearedResult.containsKey(currentSavable)) {
                saveResult(currentSavable)
                break
            }
            appearedResult[currentSavable] = Unit
        }

        saveProfiler()
        println("predict times: ${predictTimes.load() - canceledPredictTimes.load()}")
        println("predict canceled times: ${canceledPredictTimes.load()}")
        println("file cache hit times: ${fileContentsCache.values.sumOf { it.second }}")
        println("elements cache hit times: ${elementsCache.values.sumOf { it.value.second }}")
    }

    private fun saveProfiler() {
        val csv = CSVPrinter(
            targetDir.resolve("profiler.csv").writer(),
            CSVFormat.Builder.create()
                .setHeader("Time(s)", "File", "Token Count")
                .get()
        )
        for ((dur, files) in profiler) {
            for ((file, content) in files) {
                val tokens = countTokens(content, OCLanguage.getInstance(), project)
                csv.printRecord(dur.inWholeSeconds, file.removePrefix(workingDir), tokens)
            }
        }
        csv.close(true)
    }
}