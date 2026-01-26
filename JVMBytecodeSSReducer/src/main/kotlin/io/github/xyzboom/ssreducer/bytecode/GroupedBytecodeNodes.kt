package io.github.xyzboom.ssreducer.bytecode

import io.github.xyzboom.ssreducer.bytecode.nodes.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.FieldRemapper
import org.objectweb.asm.commons.MethodRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.CheckClassAdapter
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
            val recordedClasses = mutableMapOf<String, Pair<ClassNode, Path>>()
            val innerClasses = mutableListOf<Pair<ClassNode, Path>>()
            val inner2Outer = mutableMapOf<String, String>()

            fun addClass(clazz: ClassNode, path: Path, level: Int, parent: BytecodeNode? = null) {
                val classBCNode = ClassBCNode(clazz, path.relativeTo(relativeTo).pathString, parent)
                nodes[classBCNode] = level
                for (method in clazz.methods) {
                    val methodBCNode = MethodBCNode(method, classBCNode)
                    nodes[methodBCNode] = level + 1
                    // todo reduce instructions
                }
                for (field in clazz.fields) {
                    val fieldNode = FieldBCNode(field, classBCNode)
                    nodes[fieldNode] = level + 1
                }
            }

            for ((clazz, _) in classNodes) {
                for (innerClazz in clazz.innerClasses) {
                    // for somehow, we can find the inner class is the same as outer class,
                    // maybe a compiler bug? or something else? to be investigated.
                    val innerName: String? = innerClazz.name
                    val outerName = clazz.name!!
                    if (innerName == clazz.name) {
                        continue
                    }

                    if (innerName == null) continue
                    if (innerName.startsWith(outerName)) {
                        inner2Outer[innerName] = outerName
                    }
                }
            }

            for (pair in classNodes) {
                val (clazz, path) = pair
                recordedClasses[clazz.name] = pair
                if (inner2Outer[clazz.name] != null) {
                    innerClasses.add(pair)
                    continue
                }
                addClass(clazz, path, 1)
            }
            while (innerClasses.isNotEmpty()) {
                val iterator = innerClasses.iterator()
                while (iterator.hasNext()) {
                    val (clazz, path) = iterator.next()
                    val outerName = inner2Outer[clazz.name]
                    val outerPair = nodes.entries.find { (key, _) ->
                        key.name == outerName
                    }
                    if (outerPair == null) {
                        if (outerName in recordedClasses) {
                            // the outer class is also an inner class
                            continue
                        }
                        addClass(clazz, path, 1)
                        iterator.remove()
                        continue
                    }
                    val (outerNode, outerLevel) = outerPair
                    addClass(clazz, path, outerLevel + 1, outerNode)
                    iterator.remove()
                }
            }
            return GroupedBytecodeNodes(nodes, HashSet(nodes.keys))
        }
    }

    fun BytecodeNode.anyParentWasDeleted(): Boolean {
        var parent = this.parent
        while (parent != null) {
            if (parent !in nodes) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    fun removeUselessNodes(): GroupedBytecodeNodes {
        val iterator = nodes.iterator()
        while (iterator.hasNext()) {
            val (node, _) = iterator.next()
            if (node.anyParentWasDeleted()) {
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
            name: String,
            descriptor: String?,
            signature: String?,
            value: Any?
        ): FieldVisitor? {
            val fieldNode = DescOnlyBCNode("$className.$name $descriptor")
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
        private val newTypes = mutableMapOf<Label, String>()

        fun consume(type: Type) {
            super.visitFieldInsn(
                Opcodes.GETSTATIC,
                Type.getInternalName(System::class.java),
                System::out.name,
                Type.getDescriptor(System.out.javaClass)
            )
            if (type.size == 2) {
                super.visitInsn(Opcodes.DUP_X2)
                super.visitInsn(Opcodes.POP)
            } else {
                super.visitInsn(Opcodes.SWAP)
            }
            val desc = when (type.sort) {
                Type.BOOLEAN -> "(Z)V"
                Type.CHAR -> "(C)V"
                Type.BYTE -> "(B)V"
                Type.SHORT -> "(S)V"
                Type.INT -> "(I)V"
                Type.FLOAT -> "(F)V"
                Type.LONG -> "(J)V"
                Type.DOUBLE -> "(D)V"
                else -> "(${Type.getDescriptor(Object::class.java)})V"
            }
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(System.out.javaClass),
                "println",
                desc,
                false
            )
        }

        fun consumeArgs(descriptor: String) {
            // the top of the stack is the last argument
            for (type in Type.getArgumentTypes(descriptor).reversed()) {
                consume(type)
            }
        }

        private fun Type.insertArrayOf() {
            super.visitInsn(Opcodes.ICONST_0)
            when (this.sort) {
                Type.VOID -> {
                    // do nothing
                }

                Type.BOOLEAN -> super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN)
                Type.CHAR -> super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR)
                Type.BYTE -> super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE)
                Type.SHORT -> super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_SHORT)
                Type.INT -> super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
                Type.FLOAT -> super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT)
                Type.LONG -> super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG)
                Type.DOUBLE -> super.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE)
                else -> super.visitTypeInsn(Opcodes.ANEWARRAY, this.internalName)
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

                Type.OBJECT -> {
                    super.visitInsn(Opcodes.ACONST_NULL)
                }

                Type.ARRAY -> {
                    elementType.insertArrayOf()
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

            fun handleDelete() {
                consumeArgs(descriptor)
                if (opcodeAndSource == Opcodes.INVOKEVIRTUAL || opcodeAndSource == Opcodes.INVOKEINTERFACE) {
                    super.visitInsn(Opcodes.POP)
                }
                if (opcodeAndSource == Opcodes.INVOKESPECIAL) {
                    if (name == "<init>") {
                        val top = analyzer.stack.lastOrNull()
                        val newType = (top as? Label)?.let { newTypes[it] }
                        if (newType != null) {
                            super.visitMethodInsn(
                                Opcodes.INVOKESPECIAL,
                                newType, "<init>", "()V", false
                            )
                        } else {
                            super.visitMethodInsn(
                                Opcodes.INVOKESPECIAL,
                                OBJECT_NAME, "<init>", "()V", false
                            )
                        }
                    } else {
                        super.visitInsn(Opcodes.POP)
                    }
                }
                returnType.insertDefaultValue()
            }

            val methodNode = DescOnlyBCNode("$owner.$name $descriptor")
            if (typeNode.shouldBeDeleted() || methodNode.shouldBeDeleted()) {
                return handleDelete()
            }
            super.visitMethodInsn(opcodeAndSource, owner, name, descriptor, isInterface)
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            val fieldNode = DescOnlyBCNode("$owner.$name $descriptor")
            val typeNode = DescOnlyBCNode(owner)
            val oriFieldType = Type.getType(descriptor)
            val fieldType = oriFieldType.transformed()
            if (typeNode.shouldBeDeleted() || fieldNode.shouldBeDeleted()) {
                if (opcode == Opcodes.GETFIELD) {
                    super.visitInsn(Opcodes.POP)
                }
                if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                    consume(oriFieldType)
                    if (opcode == Opcodes.PUTFIELD) {
                        super.visitInsn(Opcodes.POP)
                    }
                    return
                } else {
                    return fieldType.insertDefaultValue()
                }
            }
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            super.visitTypeInsn(opcode, type)
            val transformed = Type.getObjectType(type).transformed().internalName
            if (opcode == Opcodes.NEW) {
                val label = analyzer.stack.last() as Label
                newTypes[label] = transformed
            }
        }

        override fun visitInsn(opcode: Int) {
            if (opcode == Opcodes.ATHROW) {
                val top = analyzer.stack.lastOrNull()
                if (top == OBJECT_NAME) {
                    super.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Throwable")
                    super.visitInsn(Opcodes.ATHROW)
                    return
                }
            }
            super.visitInsn(opcode)
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
    fun fileContents(verify: Boolean): Map<String, ByteArray> {
        @Suppress("UNCHECKED_CAST") // Safe cast, we checked the type in filter
        val classes = nodes.filter { it.key is ClassBCNode }.keys as Set<ClassBCNode>
        val result = mutableMapOf<String, ByteArray>()
        for (clazz in classes) {
            val asmNode = clazz.asmNode
            val classWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
            val mapper = MyClassRemapper(classWriter, MyRemapper())
            asmNode.accept(mapper)
            val resultBytes = classWriter.toByteArray()
            if (verify) {
                val reader = ClassReader(resultBytes)
                val checker = CheckClassAdapter(null, true)
                try {
                    reader.accept(checker, ClassReader.EXPAND_FRAMES)
                } catch (e: Exception) {
                    System.err.println("Verify failed for class ${clazz.name}")
                    throw e
                }
            }
            result[clazz.relativePath] = resultBytes
        }
        return result
    }

    fun copyOf(nodesNow: Map<BytecodeNode, Int>): GroupedBytecodeNodes {
        return GroupedBytecodeNodes(HashMap(nodesNow), HashSet(oriNodes)).removeUselessNodes()
    }
}