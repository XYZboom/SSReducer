package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.cidr.lang.psi.OCCallExpression
import com.jetbrains.cidr.lang.psi.OCDeclaration
import com.jetbrains.cidr.lang.psi.OCDeclarator
import com.jetbrains.cidr.lang.psi.OCExpression
import com.jetbrains.cidr.lang.psi.OCExpressionStatement
import com.jetbrains.cidr.lang.psi.OCFile
import com.jetbrains.cidr.lang.psi.OCFunctionDeclaration
import com.jetbrains.cidr.lang.psi.OCReferenceElement
import com.jetbrains.cidr.lang.psi.OCReferenceExpression
import com.jetbrains.cidr.lang.psi.OCTypeElement
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import com.jetbrains.cidr.lang.symbols.OCResolveContext
import com.jetbrains.cidr.lang.types.OCReferenceType
import com.jetbrains.cidr.lang.types.OCType
import com.jetbrains.cidr.lang.util.OCElementFactory
import io.github.xyzboom.ssreducer.PsiWrapper
import kotlin.math.max

class GroupElements(
    val project: Project,
    val elements: MutableMap<PsiWrapper<*>, Int>,
    val elementsInOriProgram: Set<PsiWrapper<*>>,
    val maxLevel: Int,
) {

    private val allScope = GlobalSearchScope.allScope(project)

    companion object {

        private val REF_TARGET_KEY = Key.create<List<PsiWrapper<*>>>("REF_TARGET_KEY")

        fun isTypedef(element: PsiElement): Boolean {
            return element is OCDeclaration && element.isTypedef
        }

        fun isDecl(element: PsiElement): Boolean {
            return element is OCDeclaration ||
                    element is OCFile // ||
        }

        fun groupElements(project: Project, files: Collection<OCFile>): GroupElements {
            val elements = mutableMapOf<PsiWrapper<*>, Int>()
            var maxLevel = 0
            for (file in files) {
                val stack = ArrayDeque<PsiElement>()
                fun enterElement(element: PsiElement) {
                    val targets = element.references.mapNotNull { it.resolve() ?: return@mapNotNull null }
                    val callTarget = if (element is OCCallExpression) {
                        val method = element.functionReferenceExpression.reference?.resolve()
//                        if (method != null && !method.shouldBeDeleted()) {
                            // todo
                            /*for ((i, param) in method.parameters.withIndex()) {
                                val sourceParam = param.sourceElement
                                if (sourceParam.shouldBeDeleted()) {
                                    element.argumentList?.expressions[i]?.let {
                                        needDeleteElements.add(PsiWrapper.of(it))
                                    }
                                }
                            }*/
//                        }
                        method
                    } else null
                    val allTargets = if (callTarget != null) targets + callTarget else targets
                    element.putCopyableUserData(REF_TARGET_KEY, allTargets.map { PsiWrapper.of(it) })
                    if (isDecl(element)) {
                        stack.addFirst(element)
                        elements[PsiWrapper.of(element)] = stack.size
                        maxLevel = max(maxLevel, stack.size)
                    }
                }

                fun exitElement(element: PsiElement) {
                    if (isDecl(element)) {
                        require(stack.removeFirst() === element)
                    }
                }

                val visitor = object : OCRecursiveVisitor() {
                    override fun visitElement(elem: PsiElement) {
                        enterElement(elem)
                        super.visitElement(elem)
                        exitElement(elem)
                    }
                }
                file.accept(visitor)
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

    fun copyOf(fromElements: Map<PsiWrapper<*>, Int>): GroupElements {
        val editedFrom = elements.keys.intersect(fromElements.keys)

        @Suppress("UNCHECKED_CAST")
        val files = editedFrom.filter { it.element is PsiFile }.map { it.element } as List<PsiFile>
        val copiedFiles = files.associateWith { it.copy() as PsiFile }
        val copiedElements = mutableMapOf<PsiWrapper<*>, Int>()
        for ((oriFile, copiedFile) in copiedFiles) {
            fun enterElement(element: PsiElement) {
                if (!isDecl(element)) {
                    return
                }
                val wrapper = PsiWrapper.of(element)
                if (wrapper !in editedFrom) {
                    return
                }
                val oriElement = PsiTreeUtil.findSameElementInCopy(element, oriFile)
                val oriLevel = fromElements[PsiWrapper.of(oriElement)]
                if (oriLevel != null) {
                    copiedElements[wrapper] = oriLevel
                }
                return
            }

            val visitor = object : OCRecursiveVisitor() {
                override fun visitElement(element: PsiElement) {
                    enterElement(element)
                    super.visitElement(element)
                }
            }
            copiedFile.accept(visitor)
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

    private fun createDefaultValueExprForType(type: OCType, context: PsiElement): OCExpression {
        return OCElementFactory.expressionFromText("*(${type.name}*)0", context)!!
    }

    private fun editTypeRef(element: PsiElement, target: PsiElement) {
        val voidPointer = OCElementFactory.typeElementFromText("void**", element.context!!)
        element.replace(voidPointer)
    }

    private fun shouldBeDeleted(element: OCType, ocContext: PsiElement): Boolean {
        if (element !is OCReferenceType) return false
        val resolveContext = OCResolveContext.forPsi(ocContext)
        val symbols = element.reference.resolveToSymbols(resolveContext)
        for (symbol in symbols) {
            val def = symbol.locateDefinition(project)
            if (def is OCDeclarator) {
                if (def.parent.shouldBeDeleted()) {
                    return true
                }
            }
            if (def.shouldBeDeleted()) {
                return true
            }
        }
        return false
    }

    fun PsiElement.replaceOrDeleteParentStatement(createNewElement: () -> PsiElement) {
        val parent = parent
        if (parent is OCExpressionStatement && parent.expression === this) {
            parent.delete()
        } else {
            replace(createNewElement())
        }
    }

    private fun editCallExpr(element: OCCallExpression, target: PsiElement) {
        if (target is OCDeclarator) {
            when (val targetParent = target.parent) {
                is OCFunctionDeclaration -> {
                    val returnType = targetParent.returnType
                    element.replaceOrDeleteParentStatement {
                        if (shouldBeDeleted(returnType, targetParent)) {
                            val pointerDepth = returnType.pointersDepth()
                            val extra = "*".repeat(pointerDepth)
                            OCElementFactory.expressionFromText("(void**${extra})0", element.context!!)!!
                        } else {
                            createDefaultValueExprForType(returnType, element.context!!)
                        }
                    }
                }
            }
        }
    }

    private fun editElement(element: PsiElement, target: PsiElement) {
        if (element is OCReferenceElement) {
            when (val parent = element.parent) {
                is OCTypeElement -> editTypeRef(element, target)
                is OCReferenceExpression -> {
                    val parent2 = parent.parent
                    if (parent2 is OCCallExpression) {
                        editCallExpr(parent2, target)
                    } else if (target.parent is OCFunctionDeclaration) {
                        element.replaceOrDeleteParentStatement {
                            OCElementFactory.expressionFromText("(void**)0", element.context!!)!!
                        }
                    }
                }
            }
        }
    }

    private fun deleteElement(element: PsiElement) {
        element.delete()
    }

    fun PsiElement?.shouldBeDeleted(shouldDelete: Set<PsiWrapper<*>> = emptySet()): Boolean {
        val wrapper = PsiWrapper.of(this ?: return false)
        if (wrapper in shouldDelete) return true
        return wrapper in elementsInOriProgram && wrapper !in elements
    }

    fun PsiElement.notPureDelete(): Boolean {
        return false // todo
    }

    fun reconstructDependencies() {
        @Suppress("UNCHECKED_CAST")
        val files = elements.keys.filter { it.element is PsiFile }.map { it.element } as List<PsiFile>
        // we must resolve reference first. Otherwise, the reference will lose after delete.
        val needEdit = mutableListOf<Pair<PsiElement, PsiElement>>()
        val needDeleteElements = mutableSetOf<PsiWrapper<PsiElement>>()

        for (file in files) {
            val isPureStack = ArrayDeque<Boolean>()

            fun recordNeedDelete(element: PsiElement): Boolean {
                if (!element.isValid) return false
                if (!isDecl(element)) return false
                if (element.shouldBeDeleted() || isPureStack.lastOrNull() == true) {
                    elements.remove(PsiWrapper.of(element))
                    needDeleteElements.add(PsiWrapper.of(element))
                    return true
                }
                return false
            }

            val visitor = object : OCRecursiveVisitor() {
                override fun visitElement(element: PsiElement) {
                    val needDelete = recordNeedDelete(element)
                    if (needDelete) isPureStack.addFirst(!element.notPureDelete())
                    super.visitElement(element)
                    if (needDelete) isPureStack.removeFirst()
                }
            }
            file.accept(visitor)
        }

        for (file in files) {
            fun recordNeedEdit(element: PsiElement) {
                val allTargets = element.getCopyableUserData(REF_TARGET_KEY) ?: emptyList()
                for (targetWrapper in allTargets) {
                    val target = targetWrapper.element
                    if (target is OCDeclarator) {
                        if (target.parent.shouldBeDeleted()) {
                            needEdit.add(element to target)
                            break
                        }
                    }
                    if (target.shouldBeDeleted()) {
//                        if (element is PsiExpression) {
//                            // record default value of current type. when editing elements, the type may be changed
//                            val defaultValue = createTypeDefaultValueElement(
//                                file as PsiJavaFile, element.type, null
//                            )
//                            element.putUserData(TYPE_DEFAULT_VALUE_KEY, defaultValue)
//                        }
                        needEdit.add(element to target)
                        break
                    }
                }
            }

            val visitor = object : OCRecursiveVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (!element.isValid) return
                    if (PsiWrapper.of(element) in needDeleteElements) {
                        // we may replace initializer with the whole variable
                        // For example: Foo foo = bar.getFoo();
                        // So we must visit the initializer
//                        if (element is PsiLocalVariable) {
//                            element.initializer?.let { super.visitElement(it) }
//                        }
                    }
                    recordNeedEdit(element)
                    super.visitElement(element)
                }

//                override fun visitAssignmentExpression(expression: OCAssignmentExpression) {
//                    if (!expression.isValid) return
//                    if (PsiWrapper.of(expression) in needDeleteElements) return
//                    // we need to edit right first because sometimes
//                    // we may replace the whole assignment with the right
//                    expression.sourceExpression?.accept(this)
//                    expression.receiverExpression.accept(this)
//                }
            }
            file.accept(visitor)
        }

        fun PsiElement.anyParentNeedDelete(): Boolean {
            if (PsiWrapper.of(this) in needDeleteElements) return true
            return parent?.anyParentNeedDelete() == true
        }

        for ((element, target) in needEdit) {
            if (!element.isValid) continue
            if (element.anyParentNeedDelete()) continue
            editElement(element, target)
        }

        for (wrapper in needDeleteElements) {
            if (!wrapper.element.isValid) continue
            try {
                deleteElement(wrapper.element)
            } catch (e: Exception) {
                e
            }
        }

//        for (file in files) {
//            if (file is PsiJavaFile) {
//                javaCodeStyleManager.shortenClassReferences(file)
//                javaCodeStyleManager.optimizeImports(file)
//            }
//        }
    }
}