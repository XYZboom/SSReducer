package io.github.xyzboom.ssreducer.bytecode.nodes

import org.objectweb.asm.tree.FieldNode

class FieldBCNode(
    override val asmNode: FieldNode,
    parent: BytecodeNode? = null
) : BytecodeNode(asmNode, parent) {
    override val name: String = asmNode.name + " " + asmNode.desc
}