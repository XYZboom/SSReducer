package io.github.xyzboom.ssreducer.bytecode

import io.github.xyzboom.ssreducer.bytecode.nodes.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.FieldRemapper
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
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String?>?
        ): MethodVisitor? {
            val methodNode = DescOnlyBCNode("$className.$name $descriptor")
            if (methodNode.shouldBeDeleted()) {
                return null
            }
            val superVisitor = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return MyMethodMapper(superVisitor, remapper, className, access, name, descriptor)
        }

        override fun visitField(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            value: Any?
        ): FieldVisitor? {
            val fieldNode = DescOnlyBCNode(name ?: return super.visitField(access, name, descriptor, signature, value))
            if (fieldNode.shouldBeDeleted()) {
                return null
            }
            val superVisitor = super.visitField(access, name, descriptor, signature, value) ?: return null
            return MyFieldMapper(superVisitor, remapper)
        }
    }

    inner class MyMethodMapper(
        methodVisitor: MethodVisitor,
        remapper: Remapper,
        owner: String, access: Int, name: String, descriptor: String
    ) : MethodRemapper(AnalyzerAdapter(owner, access, name, descriptor, methodVisitor), remapper) {

        private val analyzer get() = mv as AnalyzerAdapter

        fun consume(type: Type) {
            if (type.size == 2) {
                super.visitInsn(Opcodes.POP2)
            } else {
                super.visitInsn(Opcodes.POP)
            }
        }

        fun consumeArgs(descriptor: String) {
            for (type in Type.getArgumentTypes(descriptor)) {
                consume(type)
            }
        }

        private fun Type.insertDefaultValue() {
            when (this.sort) {
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
        }

        override fun visitMethodInsn(
            opcodeAndSource: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean
        ) {
            val typeNode = DescOnlyBCNode(owner)
            val returnType = Type.getReturnType(descriptor).transformed()

            fun insertDefaultValueOfReturnType() {
                if (opcodeAndSource == Opcodes.INVOKESPECIAL && name == "<init>") {
                    super.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        OBJECT_NAME, "<init>", "()V", false
                    )
                    // this means we have NEW and DUP before
                    // we need to pop the newly create object
                    if (analyzer.stack.size >= 2) {
                        super.visitInsn(Opcodes.POP)
                        super.visitInsn(Opcodes.ACONST_NULL)
                    }
                }
                returnType.insertDefaultValue()
            }

            if (typeNode.shouldBeDeleted()) {
                consumeArgs(descriptor)
                return insertDefaultValueOfReturnType()
            }
            val methodNode = DescOnlyBCNode("$owner.$name $descriptor")
            if (methodNode.shouldBeDeleted()) {
                consumeArgs(descriptor)
                return insertDefaultValueOfReturnType()
            }
            super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface)
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            val typeNode = DescOnlyBCNode(owner)
            val oriFieldType = Type.getType(descriptor)
            val fieldType = oriFieldType.transformed()
            if (typeNode.shouldBeDeleted()) {
                return if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                    consume(oriFieldType)
                } else {
                    return fieldType.insertDefaultValue()
                }
            }
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }
    }

    class MyFieldMapper(
        fieldVisitor: FieldVisitor,
        remapper: Remapper
    ) : FieldRemapper(Opcodes.ASM9, fieldVisitor, remapper) {

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