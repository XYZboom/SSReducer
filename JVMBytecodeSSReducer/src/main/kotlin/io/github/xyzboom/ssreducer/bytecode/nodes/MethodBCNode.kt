package io.github.xyzboom.ssreducer.bytecode.nodes

import org.objectweb.asm.tree.MethodNode

class MethodBCNode(
    override val asmNode: MethodNode,
    parent: BytecodeNode? = null
) : BytecodeNode(asmNode, parent) {
    override val name: String = asmNode.name
}