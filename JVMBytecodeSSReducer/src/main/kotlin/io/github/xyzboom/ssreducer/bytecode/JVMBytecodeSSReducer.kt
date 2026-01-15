package io.github.xyzboom.ssreducer.bytecode

import com.github.ajalt.clikt.core.main
import io.github.xyzboom.ssreducer.CommonReducer
import io.github.xyzboom.ssreducer.IReducer
import io.github.xyzboom.ssreducer.collectSourceFiles
import io.github.xyzboom.ssreducer.workingDir
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readBytes

class JVMBytecodeSSReducer : CommonReducer(workingDir), IReducer {

    private fun groupSourceFiles(sourceFiles: List<Path>) {
        for (sourceFile in sourceFiles) {
            println(sourceFile)
            val node = parseClassTree(sourceFile.readBytes())
            printClassTree(node)
        }
    }

    override fun run() {
        val sourceFiles = collectSourceFiles(sourceRoots) { it.extension == "class" }
        groupSourceFiles(sourceFiles)
    }

    override fun doReduce(args: Array<String>) {
        main(args)
    }

    override val reducerName: String
        get() = super<IReducer>.reducerName
}