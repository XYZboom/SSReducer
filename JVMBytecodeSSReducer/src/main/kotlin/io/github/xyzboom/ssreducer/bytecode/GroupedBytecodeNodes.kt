package io.github.xyzboom.ssreducer.bytecode

class GroupedBytecodeNodes private constructor() {
    companion object {
        fun groupNodes(): GroupedBytecodeNodes {
            return GroupedBytecodeNodes()
        }
    }
}