package io.github.xyzboom.ssreducer.bytecode.nodes

import org.objectweb.asm.tree.ClassNode

class ClassBCNode(
    override val asmNode: ClassNode,
    val relativePath: String,
    parent: BytecodeNode? = null
) : BytecodeNode(asmNode, parent) {
    override val name: String = asmNode.name

}