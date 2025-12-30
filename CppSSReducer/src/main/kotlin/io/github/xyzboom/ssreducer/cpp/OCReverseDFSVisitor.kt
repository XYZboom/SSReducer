package io.github.xyzboom.ssreducer.cpp

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiRecursiveVisitor

open class OCReverseDFSVisitor : PsiElementVisitor(), PsiRecursiveVisitor {
    override fun visitElement(element: PsiElement) {
        super.visitElement(element)
        var child = element.lastChild
        while (child != null) {
            visitElement(child)
            child = child.prevSibling
        }
    }
}