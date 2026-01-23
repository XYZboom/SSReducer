package io.github.xyzboom.ssreducer.bytecode.nodes

sealed class BytecodeNode protected constructor(
    open val asmNode: Any,
    val parent: BytecodeNode? = null
) {
    abstract val name: String
    open val qualifiedName: String
        get() = if (parent != null) {
            "${parent.qualifiedName}.$name"
        } else {
            name
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BytecodeNode) return false

        if (qualifiedName != other.qualifiedName) return false

        return true
    }

    override fun hashCode(): Int {
        return qualifiedName.hashCode()
    }

    override fun toString(): String {
        return qualifiedName
    }
}