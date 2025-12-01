package io.github.xyzboom.ssreducer.kotlin

import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import kotlin.reflect.cast

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