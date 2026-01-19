package io.github.xyzboom.ssreducer

import com.intellij.lang.Language
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType

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

fun countTokens(text: String, language: Language, project: Project): Int {
    val lexer = LanguageParserDefinitions.INSTANCE
        .forLanguage(language)
        .createLexer(project)
    lexer.start(text)
    var count = 0
    while (true) {
        val tokenType = lexer.tokenType ?: break
        if (tokenType != TokenType.WHITE_SPACE) {
            count++
        }
        lexer.advance()
    }
    return count
}