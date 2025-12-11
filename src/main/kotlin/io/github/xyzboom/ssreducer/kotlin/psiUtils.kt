package io.github.xyzboom.ssreducer.kotlin

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiCapturedWildcardType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import io.github.xyzboom.ssreducer.PsiWrapper

fun PsiElement.canBeStatement(): Boolean {
    return this is PsiCallExpression
}

inline fun <reified T> PsiElement?.parentOfTypeAndDirectChild(): Pair<T, PsiElement>? {
    if (this == null) return null

    if (this is PsiFile) {
        return null
    }
    var child = this
    var element = parent

    while (element != null) {
        if (T::class.isInstance(element)) {
            return element as T to child!!
        }
        if (element is PsiFile) {
            return null
        }
        child = element
        element = element.parent
    }

    return null
}

fun PsiType.getReferencedClasses(): Set<PsiWrapper<PsiClass>> {
    val result = mutableSetOf<PsiWrapper<PsiClass>>()
    collectClasses(result)
    return result
}

private fun PsiType.collectClasses(set: MutableSet<PsiWrapper<PsiClass>>) {
    if (this is PsiClassType) {
        val ref = this.resolve()
        if (ref != null) {
            set.add(PsiWrapper.of(ref))
        }
        for (param in this.parameters) {
            param.collectClasses(set)
        }
    }
    if (this is PsiArrayType) {
        this.componentType.collectClasses(set)
    }
    if (this is PsiWildcardType) {
        this.bound?.collectClasses(set)
    }
    if (this is PsiCapturedWildcardType) {
        this.lowerBound?.collectClasses(set)
        this.upperBound.collectClasses(set)
    }
}