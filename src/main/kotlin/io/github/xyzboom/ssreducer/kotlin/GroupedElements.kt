package io.github.xyzboom.ssreducer.kotlin

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

data class GroupedElements(
    val project: Project,
    val files: Set<PsiFile>,
    val javaClasses: Set<PsiClass>,
    val ktClasses: Set<KtClass>,
    val javaMethods: Set<PsiMethod>,
    val ktMethods: Set<KtNamedFunction>,
    val elements: Set<PsiElement>,
    val original: Set<PsiElement>,
    val copyMap: Map<PsiFile, PsiFile>
) {
    private val javaParserFacade: PsiElementFactory = project.getService(PsiElementFactory::class.java)

    companion object {
        fun groupElements(
            project: Project,
            rootElements: Collection<PsiFile>, copyMap: Map<PsiFile, PsiFile>
        ): GroupedElements {
            val elements = mutableSetOf<PsiElement>()
            val original = mutableSetOf<PsiElement>()
            val files = rootElements.toMutableSet()
            val javaClasses = mutableSetOf<PsiClass>()
            val ktClasses = mutableSetOf<KtClass>()
            val javaMethods = mutableSetOf<PsiMethod>()
            val ktMethods = mutableSetOf<KtNamedFunction>()
            for (element in rootElements) {
                element.accept(object : JavaRecursiveElementWalkingVisitor() {
                    override fun visitClass(aClass: PsiClass) {
                        javaClasses += aClass
                        super.visitClass(aClass)
                    }

                    override fun visitMethod(method: PsiMethod) {
                        javaMethods += method
                        super.visitMethod(method)
                    }

                    override fun visitElement(element: PsiElement) {
                        elements.add(element)
                        original.add(element.originalElement)
                        super.visitElement(element)
                    }
                })
                element.accept(object : KtTreeVisitorVoid() {
                    override fun visitClass(klass: KtClass) {
                        ktClasses += klass
                        super.visitClass(klass)
                    }

                    override fun visitNamedFunction(function: KtNamedFunction) {
                        ktMethods += function
                        super.visitNamedFunction(function)
                    }

                    override fun visitElement(element: PsiElement) {
                        elements.add(element)
                        original.add(element.originalElement)
                        super.visitElement(element)
                    }
                })
            }
            return GroupedElements(
                project,
                files, javaClasses, ktClasses, javaMethods, ktMethods, elements, original, copyMap
            )
        }
    }

    fun shouldEdit(element: PsiElement, target: PsiElement): Boolean {
        if (target is PsiClass) {
            return true
        }
        // todo other cases
        return false
    }

    fun editElement(element: PsiElement, target: PsiElement) {
        if (target is PsiClass) {
            val parent = element.parent
            if (parent !is PsiReferenceExpression) {
                element.replace(javaParserFacade.createTypeElementFromText("Object", element.context))
            } else {
                var parent = element.parent
                while (parent.parent is PsiReferenceExpression) {
                    parent = parent.parent
                }
                parent.replace(javaParserFacade.createExpressionFromText("null", element.context))
            }
        }
        // todo other cases
    }

    fun reconstructDependencies() {
        val needEdit = mutableMapOf<PsiElement, PsiElement>()
        for (file in files) {
            file.accept(object : JavaRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (!element.isValid) {
                        println()
                    }
                    val targets = element.references.mapNotNull {
                        val target = it.resolve() ?: return@mapNotNull null
                        val targetFile = target.containingFile
                        if (targetFile === it.element.containingFile) {
                            return@mapNotNull target
                        }
                        val copiedTarget = copyMap[targetFile]
                        if (copiedTarget != null) {
                            return@mapNotNull PsiTreeUtil.findSameElementInCopy(target, copiedTarget)
                        }
                        return@mapNotNull target
                    }
                    for (target in targets) {
                        if (target.containingFile !in files) {
                            if (shouldEdit(element, target)) {
                                needEdit[element] = target
                                return
                            }
                            break
                        }
                    }
                    super.visitElement(element)
                }
            })
        }
        for ((element, target) in needEdit) {
            editElement(element, target)
        }
    }

    fun fileContents(): Map<String, String> {
        return files.associate { it.originalFile.virtualFile.path to it.text!! }
    }
}