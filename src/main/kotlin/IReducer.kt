package io.github.xyzboom.ssreducer

interface IReducer {
    val reducerName: String
        get() = this::class.simpleName!!
    fun doReduce(args: Array<String>)
}