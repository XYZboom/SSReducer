package io.github.xyzboom.ssreducer.kotlin

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import kotlin.math.max

class GroupElements(
    val project: Project,
    val elements: Map<PsiWrapper, Int>,
    val elementsInOriProgram: Set<PsiWrapper>,
    val maxLevel: Int,
) {
    private val javaParserFacade: PsiElementFactory = project.getService(PsiElementFactory::class.java)

    open class JavaOrKotlinElementVisitor {
        open fun enterElement(element: PsiElement) {}
        open fun exitElement(element: PsiElement) {}

        fun doVisit(element: PsiElement) {
            if (element is KtElement) {
                element.accept(object : KtTreeVisitorVoid() {
                    override fun visitElement(element: PsiElement) {
                        enterElement(element)
                        super.visitElement(element)
                        exitElement(element)
                    }
                })
            } else {
                element.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        enterElement(element)
                        super.visitElement(element)
                        exitElement(element)
                    }
                })
            }
        }
    }

    companion object {

        fun isDecl(element: PsiElement): Boolean {
            return element is PsiNamedElement
        }

        fun groupElements(project: Project, files: Collection<PsiFile>): GroupElements {
            val elements = mutableMapOf<PsiWrapper, Int>()
            var maxLevel = 0
            for (file in files) {
                val visitor = object : JavaOrKotlinElementVisitor() {
                    val stack = ArrayDeque<PsiElement>()
                    override fun enterElement(element: PsiElement) {
                        stack.addFirst(element)
                        if (isDecl(element)) {
                            elements[PsiWrapper(element, true)] = stack.size
                        }
                        maxLevel = max(maxLevel, stack.size)
                    }

                    override fun exitElement(element: PsiElement) {
                        require(stack.removeFirst() === element)
                    }
                }
                visitor.doVisit(file)
            }
            return GroupElements(
                project, elements, HashSet(elements.keys), maxLevel
            )
        }
    }

    fun applyEdit(): GroupElements {
        return GroupElements(
            project, elements, HashSet(elements.keys), maxLevel
        )
    }

    fun copyOf(fromElements: Map<PsiWrapper, Int>): GroupElements {
        val editedFrom = elements.keys.intersect(fromElements.keys)

        @Suppress("UNCHECKED_CAST")
        val files = editedFrom.filter { it.element is PsiFile }.map { it.element } as List<PsiFile>
        val copiedFiles = files.associateWith { it.copy() as PsiFile }
        val copiedElements = mutableMapOf<PsiWrapper, Int>()
        for ((oriFile, copiedFile) in copiedFiles) {
            val visitor = object : JavaOrKotlinElementVisitor() {
                override fun enterElement(element: PsiElement) {
                    if (!isDecl(element)) {
                        return
                    }
                    val oriElement = PsiTreeUtil.findSameElementInCopy(element, oriFile)
                    val oriLevel = fromElements[PsiWrapper(oriElement)]
                    if (oriLevel != null) {
                        copiedElements[PsiWrapper(element)] = oriLevel
                    }
                }
            }
            visitor.doVisit(copiedFile)
        }
        return GroupElements(
            project, copiedElements, elementsInOriProgram, maxLevel
        )
    }

    fun fileContents(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val files = elements.keys.filter { it.element is PsiFile }.map { it.element } as List<PsiFile>
        return files.associate { it.originalFile.virtualFile.path to it.text!! }
    }

    fun replaceChainAccessWithNull(element: PsiReferenceExpression) {
        var parent: PsiElement = element
        while (parent.parent is PsiReferenceExpression) {
            parent = parent.parent
        }
        val target = parent.reference?.resolve()
        // todo: "null;" is not correct. Delete whole statement when the only expression is "null".
        when (target) {
            is PsiField -> parent.replace(javaParserFacade.createExpressionFromText("null", parent.context))
            is PsiMethod -> parent.parent.replace(
                javaParserFacade.createExpressionFromText(
                    "null",
                    parent.parent.context
                )
            )

            else -> TODO()
        }
    }

    fun editElementRef2Class(element: PsiElement, target: PsiClass) {
        val parent = element.parent ?: return
        if (parent !is PsiReferenceExpression) {
            // todo new expression should replace parameter
            element.replace(javaParserFacade.createTypeElementFromText("Object", element.context))
        } else {
            // todo edit method or field but not class.
            replaceChainAccessWithNull(parent)
        }
    }

    val PsiType.defaultValue: String
        get() = if (this is PsiClassType) {
            val clazz = resolve()
            if (clazz != null) {
                val wrapper = PsiWrapper(clazz)
                if (wrapper in elementsInOriProgram && wrapper !in elements) {
                    "((Object) null)"
                } else {
                    "((${clazz.qualifiedName!!}) null)"
                }
            } else canonicalText
        } else if (this is PsiPrimitiveType) when {
            this === PsiTypes.byteType() -> "(java.lang.Byte.parseByte(\"1\"))"
            this === PsiTypes.charType() -> "('1')"
            this === PsiTypes.doubleType() -> "(1.0)"
            this === PsiTypes.floatType() -> "(1.0f)"
            this === PsiTypes.intType() -> "(1)"
            this === PsiTypes.longType() -> "(1L)"
            this === PsiTypes.shortType() -> "(java.lang.Short.parseShort(\"1\"))"
            this === PsiTypes.booleanType() -> "(true)"
            this === PsiTypes.voidType() || this === PsiTypes.nullType() -> "(null)"
            else -> throw NoWhenBranchMatchedException()
        }
        else canonicalText


    fun getDefaultValueTextForMethod(method: PsiMethod): String {
        val returnType = method.returnType
        if (returnType != null) {
            return returnType.defaultValue
        } else {
            val defaultClassName = method.containingClass?.qualifiedName
            return defaultClassName ?: "((Object) null)"
        }
    }

    fun PsiElement.replaceOrDeleteParentStatement(createNewElement: () -> PsiElement) {
        val parent = parent
        if (parent is PsiExpressionStatement && parent.expression === this) {
            parent.delete()
        } else {
            replace(createNewElement())
        }
    }

    private fun editElementRef2Method(element: PsiElement, target: PsiMethod) {
        val parent = element.parent ?: return
        when (parent) {
            is PsiCallExpression -> {
                parent.replaceOrDeleteParentStatement {
                    javaParserFacade.createExpressionFromText(getDefaultValueTextForMethod(target), parent.context)
                }
            }
        }
    }

    private fun editElementRef2Field(element: PsiElement, target: PsiField) {
        val parent = element.parent ?: return
        if (parent !is PsiAssignmentExpression) {
            element.replaceOrDeleteParentStatement {
                javaParserFacade.createExpressionFromText(
                    target.type.defaultValue, element.context
                )
            }
        } else {
            if (parent.lExpression === element) {
                val rExpr = parent.rExpression
                if (rExpr != null) {
                    parent.replace(rExpr)
                } else {
                    // the assignment expression is unfinished.
                    // for example: `A a = ;`
                    parent.replaceOrDeleteParentStatement {
                        javaParserFacade.createExpressionFromText(
                            target.type.defaultValue, parent.context
                        )
                    }
                }
            } else {
                element.replaceOrDeleteParentStatement {
                    javaParserFacade.createExpressionFromText(
                        target.type.defaultValue, element.context
                    )
                }
            }
        }
    }

    fun editElement(element: PsiElement, target: PsiElement) {
        when (target) {
            is PsiClass -> editElementRef2Class(element, target)
            is PsiMethod -> editElementRef2Method(element, target)
            is PsiField -> editElementRef2Field(element, target)
        }
        // todo other cases
    }

    fun shouldEdit(element: PsiElement, target: PsiElement): Boolean {
        if (target is PsiClass) {
            return true
        }
        if (target is PsiMethod) {
            return true
        }
        if (target is PsiField) {
            return true
        }
        // todo other cases
        return false
    }

    fun reconstructDependencies() {
        @Suppress("UNCHECKED_CAST")
        val files = elements.keys.filter { it.element is PsiFile }.map { it.element } as List<PsiFile>
        // we must resolve reference first. Otherwise, the reference will lose after delete.
        val needEdit = mutableListOf<Pair<PsiElement, PsiElement>>()
        for (file in files) {
            val visitor = object : JavaOrKotlinElementVisitor() {
                override fun enterElement(element: PsiElement) {
                    if (!element.isValid) {
                        return
                    }

                    val targets = element.references.mapNotNull { it.resolve() ?: return@mapNotNull null }
                    for (target in targets) {
                        val wrapTarget = PsiWrapper(target)
                        if (wrapTarget in elementsInOriProgram && wrapTarget !in elements) {
                            if (shouldEdit(element, target)) {
                                needEdit.add(element to target)
                                break
                            }
                        }
                    }
                }
            }
            visitor.doVisit(file)
        }

        val needDeleteElements = mutableSetOf<PsiElement>()
        for (file in files) {
            val visitor = object : JavaOrKotlinElementVisitor() {
                override fun enterElement(element: PsiElement) {
                    if (!element.isValid) return
                    if (!isDecl(element)) return
                    if (PsiWrapper(element) !in elements) {
                        needDeleteElements.add(element)
                    }
                }
            }
            visitor.doVisit(file)
        }

        fun PsiElement.anyParentNeedDelete(): Boolean {
            if (this in needDeleteElements) return true
            if (parent == null) return false
            return parent.anyParentNeedDelete()
        }

        for ((element, target) in needEdit) {
            if (!element.isValid) continue
            if (element.anyParentNeedDelete()) continue
            editElement(element, target)
        }

        for (element in needDeleteElements) {
            try {
                element.delete()
            } catch (e: Exception) {
                e
            }
        }
    }
}