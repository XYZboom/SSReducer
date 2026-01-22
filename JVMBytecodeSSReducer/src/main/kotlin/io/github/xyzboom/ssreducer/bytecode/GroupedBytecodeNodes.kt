package io.github.xyzboom.ssreducer.bytecode

import io.github.xyzboom.ssreducer.bytecode.nodes.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.MethodRemapper
import org.objectweb.asm.commons.Remapper
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

class GroupedBytecodeNodes private constructor(
    val nodes: MutableMap<BytecodeNode, Int>,
    /**
     * @see BytecodeNode.qualifiedName
     */
    private val oriNodes: MutableSet<BytecodeNode>
) {
    companion object {

        private const val OBJECT_NAME = "java/lang/Object"
        private val objectType = Type.getObjectType(OBJECT_NAME)

        fun groupNodes(sourceFiles: List<Path>, relativeTo: Path): GroupedBytecodeNodes {
            val classNodes = sourceFiles.map {
                val classNode = parseClassNode(it.readBytes())
                classNode to it.absolute()
            }
            val nodes = mutableMapOf<BytecodeNode, Int>()
            for ((clazz, path) in classNodes) {
                val classBCNode = ClassBCNode(clazz, path.relativeTo(relativeTo).pathString)
                nodes[classBCNode] = 1
                for (method in clazz.methods) {
                    val methodBCNode = MethodBCNode(method, classBCNode)
                    nodes[methodBCNode] = 2
                    // todo reduce instructions
                }
                for (field in clazz.fields) {
                    val fieldNode = FieldBCNode(field, classBCNode)
                    nodes[fieldNode] = 2
                }
            }
            return GroupedBytecodeNodes(nodes, HashSet(nodes.keys))
        }
    }

    fun applyEdit(): GroupedBytecodeNodes {
        val iterator = nodes.iterator()
        while (iterator.hasNext()) {
            val (node, _) = iterator.next()
            if (node.parent != null && node.parent !in nodes) {
                iterator.remove()
            }
        }
        return this
    }

    fun Type.shouldBeDeleted(): Boolean {
        val typeNode = DescOnlyBCNode(this.internalName ?: return false)
        return typeNode.shouldBeDeleted()
    }

    fun Type.transformed(): Type {
        return if (shouldBeDeleted()) {
            objectType
        } else {
            this
        }
    }

    fun BytecodeNode.shouldBeDeleted(): Boolean {
        return this in oriNodes && this !in nodes
    }

    inner class MyRemapper : Remapper(Opcodes.ASM9) {
        override fun map(internalName: String?): String? {
            internalName ?: return null
            val typeNode = DescOnlyBCNode(internalName)
            if (typeNode.shouldBeDeleted()) {
                return OBJECT_NAME
            }
            return super.map(internalName)
        }
    }

    inner class MyClassRemapper(
        classVisitor: ClassVisitor, remapper: Remapper
    ) : ClassRemapper(classVisitor, remapper) {
        override fun visitInnerClass(
            name: String?, outerName: String?, innerName: String?, access: Int
        ) {
            val typeNode = DescOnlyBCNode(name ?: return super.visitInnerClass(name, outerName, innerName, access))
            if (typeNode.shouldBeDeleted()) {
                return
            }
            return super.visitInnerClass(name, outerName, innerName, access)
        }

        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String?>?
        ): MethodVisitor? {
            val methodNode = DescOnlyBCNode("$className.$name $descriptor")
            if (methodNode.shouldBeDeleted()) {
                return null
            }
            val superVisitor = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return MyMethodMapper(superVisitor, remapper)
        }
    }

    inner class MyMethodMapper(
        methodVisitor: MethodVisitor,
        remapper: Remapper
    ) : MethodRemapper(methodVisitor, remapper) {
        fun newObject() {
            super.visitTypeInsn(Opcodes.NEW, OBJECT_NAME)
            super.visitInsn(Opcodes.DUP)
            super.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                OBJECT_NAME,
                "<init>",
                "()V",
                false
            )
        }

        fun consumeArgs(descriptor: String) {
            for (type in Type.getArgumentTypes(descriptor)) {
                if (type.size == 2) {
                    super.visitInsn(Opcodes.POP2)
                } else {
                    super.visitInsn(Opcodes.POP)
                }
            }
        }

        override fun visitMethodInsn(
            opcodeAndSource: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
        ) {
            val typeNode = DescOnlyBCNode(owner)
            if (typeNode.shouldBeDeleted()) {
                consumeArgs(descriptor)
                return if (opcodeAndSource != Opcodes.INVOKESPECIAL) {
                    newObject()
                } else {
                    super.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        OBJECT_NAME,
                        "<init>",
                        "()V",
                        false
                    )
                }
            }
            val methodNode = DescOnlyBCNode("$owner.$name $descriptor")
            if (methodNode.shouldBeDeleted()) {
                consumeArgs(descriptor)
                return if (opcodeAndSource != Opcodes.INVOKESPECIAL) {
                    val returnType = Type.getReturnType(descriptor).transformed()
                    when (returnType.sort) {
                        Type.VOID -> {
                            // do nothing
                        }
                        Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> {
                            super.visitInsn(Opcodes.ICONST_0)
                        }
                        Type.FLOAT -> {
                            super.visitInsn(Opcodes.FCONST_0)
                        }
                        Type.LONG -> {
                            super.visitInsn(Opcodes.LCONST_0)
                        }
                        Type.DOUBLE -> {
                            super.visitInsn(Opcodes.DCONST_0)
                        }
                        else -> {
                            super.visitInsn(Opcodes.ACONST_NULL)
                        }
                    }
                } else {
                    super.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        OBJECT_NAME,
                        "<init>",
                        "()V",
                        false
                    )
                }
            }
            super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface)
        }

        override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
            val typeNode = DescOnlyBCNode(owner ?: return super.visitFieldInsn(opcode, owner, name, descriptor))
            if (typeNode.shouldBeDeleted()) {
                return newObject()
            }
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }
    }

    /**
     * Dependencies are reconstructed during generate new content.
     */
    fun fileContents(): Map<String, ByteArray> {
        @Suppress("UNCHECKED_CAST") // Safe cast, we checked the type in filter
        val classes = nodes.filter { it.key is ClassBCNode }.keys as Set<ClassBCNode>
        val result = mutableMapOf<String, ByteArray>()
        for (clazz in classes) {
            val asmNode = clazz.asmNode
            val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
            val mapper = MyClassRemapper(classWriter, MyRemapper())
            asmNode.accept(mapper)
            result[clazz.relativePath] = classWriter.toByteArray()
        }
        return result
    }

    fun copyOf(nodesNow: Map<BytecodeNode, Int>): GroupedBytecodeNodes {
        return GroupedBytecodeNodes(HashMap(nodesNow), HashSet(oriNodes))
    }
}