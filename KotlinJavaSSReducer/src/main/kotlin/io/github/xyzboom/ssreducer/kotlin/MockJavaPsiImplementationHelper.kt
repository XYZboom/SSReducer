package io.github.xyzboom.ssreducer.kotlin

import com.intellij.lang.ASTNode
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiImportList
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiJavaModule
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiType
import com.intellij.psi.impl.JavaPsiImplementationHelper
import com.intellij.psi.impl.JavaPsiImplementationHelperImpl
import com.intellij.psi.javadoc.PsiSnippetAttributeValue
import com.intellij.psi.util.PsiUtil

class MockJavaPsiImplementationHelper(private val project: Project) : JavaPsiImplementationHelper() {
    private val impl = JavaPsiImplementationHelperImpl(project)
    override fun getOriginalClass(psiClass: PsiClass): PsiClass {
        return psiClass
    }

    override fun getOriginalModule(module: PsiJavaModule): PsiJavaModule {
        return module
    }

    override fun getClsFileNavigationElement(clsFile: PsiJavaFile): PsiElement {
        return clsFile
    }

    override fun getEffectiveLanguageLevel(virtualFile: VirtualFile?): LanguageLevel {
        return PsiUtil.getLanguageLevel(project)
    }

    override fun getDefaultImportAnchor(
        list: PsiImportList,
        statement: PsiImportStatementBase
    ): ASTNode? {
        return null
    }

    override fun getDefaultMemberAnchor(
        psiClass: PsiClass,
        firstPsi: PsiMember
    ): PsiElement? {
        return null
    }

    override fun setupCatchBlock(
        exceptionName: String,
        exceptionType: PsiType,
        context: PsiElement?,
        element: PsiCatchSection
    ) {
        impl.setupCatchBlock(exceptionName, exceptionType, context, element)
    }

    override fun getSnippetRegionSymbol(value: PsiSnippetAttributeValue): PsiSymbolReference {
        return impl.getSnippetRegionSymbol(value)
    }
}