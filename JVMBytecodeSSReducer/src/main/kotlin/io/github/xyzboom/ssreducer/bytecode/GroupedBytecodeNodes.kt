package io.github.xyzboom.ssreducer.bytecode

import io.github.xyzboom.ssreducer.bytecode.nodes.BytecodeNode
import io.github.xyzboom.ssreducer.bytecode.nodes.ClassBCNode
import io.github.xyzboom.ssreducer.bytecode.nodes.DescOnlyBCNode
import io.github.xyzboom.ssreducer.bytecode.nodes.FieldBCNode
import io.github.xyzboom.ssreducer.bytecode.nodes.MethodBCNode
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
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
            asmNode.accept(object : ClassVisitor(Opcodes.ASM9, classWriter) {
                override fun visitField(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    value: Any?
                ): FieldVisitor? {
                    val field = FieldNode(access, name, descriptor, signature, value)
                    val fieldBCNode = FieldBCNode(field, clazz)
                    if (fieldBCNode !in nodes) {
                        return null
                    }
                    val typeNode = DescOnlyBCNode(Type.getType(descriptor).className)
                    if (typeNode !in oriNodes) {
                        return super.visitField(access, name, descriptor, signature, value)
                    }
                    if (typeNode !in nodes) {
                        return super.visitField(
                            access,
                            name,
                            Type.getType(Any::class.java).descriptor,
                            signature,
                            value
                        )
                    }
                    return super.visitField(access, name, descriptor, signature, value)
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String?>?
                ): MethodVisitor? {
                    val method = MethodNode(access, name, descriptor, signature, exceptions)
                    val methodBCNode = MethodBCNode(method, clazz)
                    if (methodBCNode !in nodes) {
                        return null
                    }

                    return super.visitMethod(access, name, descriptor, signature, exceptions)
                }
            })
            result[clazz.relativePath] = classWriter.toByteArray()
        }
        return result
    }

    fun copyOf(nodesNow: Map<BytecodeNode, Int>): GroupedBytecodeNodes {
        return GroupedBytecodeNodes(nodesNow.toMutableMap(), oriNodes)
    }
}