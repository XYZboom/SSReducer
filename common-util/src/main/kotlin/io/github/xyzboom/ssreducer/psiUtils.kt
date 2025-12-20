package io.github.xyzboom.ssreducer

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

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

inline fun PsiElement.eligibleParent(predicate: (PsiElement) -> Boolean): PsiElement? {
    var element: PsiElement? = this
    while (element != null) {
        if (predicate(element)) {
            return element
        }
        element = element.parent
    }
    return null
}