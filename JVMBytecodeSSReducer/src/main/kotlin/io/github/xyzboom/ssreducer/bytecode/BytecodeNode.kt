package io.github.xyzboom.ssreducer.bytecode

@JvmInline
value class BytecodeNode(val asmNode: Any) {
    companion object {

    }
    enum class NodeType {
        CLASS,
        METHOD,
        FIELD,
        INSTRUCTION
    }

    val type: NodeType
        get() = when (asmNode) {
            is org.objectweb.asm.tree.ClassNode -> NodeType.CLASS
            is org.objectweb.asm.tree.MethodNode -> NodeType.METHOD
            is org.objectweb.asm.tree.FieldNode -> NodeType.FIELD
            is org.objectweb.asm.tree.AbstractInsnNode -> NodeType.INSTRUCTION
            else -> throw IllegalArgumentException("Unknown ASM node type: ${asmNode::class.java.name}")
        }
}