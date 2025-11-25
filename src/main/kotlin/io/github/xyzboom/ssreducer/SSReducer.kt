package io.github.xyzboom.ssreducer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.ProcessedArgument
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.types.choice
import java.util.ServiceLoader

class SSReducer : CliktCommand() {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SSReducer().main(args)
        }
    }
    val reducer by run<ProcessedArgument<IReducer, IReducer>> {
        val availableReducers: Iterable<IReducer> = ServiceLoader.load(IReducer::class.java)
        val reducerMap = availableReducers.associateBy { it.reducerName }
        argument().choice(reducerMap)
    }
    val args by argument().multiple()
    override fun run() {
        reducer.doReduce(args.toTypedArray())
    }
}