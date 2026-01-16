package io.github.xyzboom.ssreducer.bytecode

import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.ClassReader

fun parseClassNode(bytecode: ByteArray): ClassNode {
    val classNode = ClassNode()
    val classReader = ClassReader(bytecode)
    classReader.accept(classNode, ClassReader.EXPAND_FRAMES)
    return classNode
}

/**
 * 打印类结构树
 */
fun printClassTree(classNode: ClassNode) {
    println("类: ${classNode.name}")
    println("访问修饰符: ${classNode.access}")
    println("父类: ${classNode.superName}")
    println("接口: ${classNode.interfaces.joinToString()}")

    println("\n字段:")
    classNode.fields.forEach { field ->
        println("  - ${field.name}: ${field.desc} (access: ${field.access})")
    }

    println("\n方法:")
    classNode.methods.forEach { method ->
        printMethodTree(method)
    }
}

/**
 * 打印方法指令树
 */
private fun printMethodTree(method: MethodNode) {
    println("  ${method.name}${method.desc}")
    println("    访问修饰符: ${method.access}")
    println("    最大栈大小: ${method.maxStack}")
    println("    本地变量数: ${method.maxLocals}")

    if (method.instructions != null && method.instructions.size() > 0) {
        println("    指令:")
        val instructions = method.instructions.toArray()
        for (insn in instructions) {
            when (insn) {
                is LineNumberNode -> {
                    println("      Line ${insn.line}:")
                }
                is LabelNode -> {
                    println("      Label: ${insn.label}")
                }
                else -> {
                    println("        ${insn.opcode}: ${insn.javaClass.simpleName}")
                    // 根据需要提取更多指令信息
                    printInstructionDetails(insn)
                }
            }
        }
    }
}

private fun printInstructionDetails(insn: AbstractInsnNode) {
    when (insn) {
        is FieldInsnNode -> {
            println("          Field: ${insn.owner}.${insn.name} ${insn.desc}")
        }
        is MethodInsnNode -> {
            println("          Method: ${insn.owner}.${insn.name}${insn.desc}")
        }
        is VarInsnNode -> {
            println("          Variable: ${insn.`var`}")
        }
        is LdcInsnNode -> {
            println("          Constant: ${insn.cst}")
        }
        is JumpInsnNode -> {
            println("          Jump to: ${insn.label.label}")
        }
    }
}