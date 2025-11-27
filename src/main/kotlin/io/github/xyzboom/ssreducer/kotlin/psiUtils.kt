package io.github.xyzboom.ssreducer.kotlin

import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiElement

fun PsiElement.canBeStatement(): Boolean {
    return this is PsiCallExpression
}