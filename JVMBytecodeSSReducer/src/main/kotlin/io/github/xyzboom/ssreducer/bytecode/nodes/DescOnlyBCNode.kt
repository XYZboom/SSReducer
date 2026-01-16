package io.github.xyzboom.ssreducer.bytecode.nodes

class DescOnlyBCNode(
    override val asmNode: String,
    parent: BytecodeNode? = null
) : BytecodeNode(asmNode, parent) {
    override val name: String = asmNode
}