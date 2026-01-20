package io.github.xyzboom.ssreducer.bytecode

import com.github.ajalt.clikt.core.main
import io.github.xyzboom.ssreducer.CommonReducer
import io.github.xyzboom.ssreducer.IReducer
import io.github.xyzboom.ssreducer.ISavable
import io.github.xyzboom.ssreducer.algorithm.DDMin
import io.github.xyzboom.ssreducer.algorithm.DDMinConcurrent
import io.github.xyzboom.ssreducer.bytecode.nodes.BytecodeNode
import io.github.xyzboom.ssreducer.collectSourceFiles
import io.github.xyzboom.ssreducer.workingDir
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.io.path.extension

class JVMBytecodeSSReducer : CommonReducer(workingDir), IReducer {

    @OptIn(ExperimentalAtomicApi::class)
    override fun run() {
        val sourceFiles = collectSourceFiles(sourceRoots) { it.extension == "class" }
        val groupedNodes = GroupedBytecodeNodes.groupNodes(sourceFiles, Path(workingDir))
        var currentGroup = groupedNodes.copyOf(groupedNodes.nodes)
        var currentContent: Map<String, ISavable> = emptyMap()
        while (true) {
            var currentLevel = 1
            // todo remove hardcoded level
            while (currentLevel <= 2) {
                val currentNodes = currentGroup.nodes.filter { it.value == currentLevel }.keys.toList()
                if (currentNodes.isEmpty()) {
                    currentLevel++
                    continue
                }
                val notCurrenNodes = currentGroup.nodes.filter { it.value != currentLevel }
                val predict: (List<BytecodeNode>) -> Pair<Boolean, Pair<GroupedBytecodeNodes, Map<String, ISavable>>> =
                    DDMin@{ remainNodes ->
                        val nodesNow = notCurrenNodes + remainNodes.associateWith { currentLevel }
                        val remainGroup = currentGroup.copyOf(nodesNow)
                        val fileContents = remainGroup.fileContents().asSavable()
                        val predictResult = predict(fileContents)
                        return@DDMin predictResult to (currentGroup to currentContent)
                    }
                val onSuccess: (List<BytecodeNode>, Pair<GroupedBytecodeNodes, Map<String, ISavable>>) -> Unit =
                    onSuccess@ { _, (remainGroup, fileContents) ->
                        currentGroup = remainGroup.applyEdit()
                        currentContent = fileContents
                    }
                val ddmin = if (jobs == 1) {
                    DDMin(predict, onSuccess)
                } else {
                    DDMinConcurrent(jobs, predict, onSuccess)
                }
                ddmin.execute(currentNodes)
                currentLevel++
            }
            if (appearedResult.containsKey(currentContent)) {
                saveResult(currentContent)
                break
            }
            appearedResult[currentContent] = Unit
        }
        println("predict times: $predictTimes")
        println("cache hit times: ${fileContentsCache.values.sumOf { it.second }}")
    }

    override fun doReduce(args: Array<String>) {
        main(args)
    }

    override val reducerName: String
        get() = super<IReducer>.reducerName
}