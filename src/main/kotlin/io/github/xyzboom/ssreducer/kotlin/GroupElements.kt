package io.github.xyzboom.ssreducer.kotlin

import com.intellij.lang.jvm.types.JvmType
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtVisitorVoid
import kotlin.math.max

@Suppress("UnstableApiUsage")
class GroupElements(
    val project: Project,
    val elements: MutableMap<PsiWrapper<*>, Int>,
    val elementsInOriProgram: Set<PsiWrapper<*>>,
    val maxLevel: Int,
) {
    private val javaParserFacade: PsiElementFactory = project.getService(PsiElementFactory::class.java)

    companion object {

        fun isDecl(element: PsiElement): Boolean {
            return when (element) {
                is PsiParameter -> element.parent !is PsiCatchSection
                is PsiNamedElement -> true
                is PsiStatement -> true
                else -> false
            }
        }

        fun groupElements(project: Project, files: Collection<PsiFile>): GroupElements {
            val elements = mutableMapOf<PsiWrapper<*>, Int>()
            var maxLevel = 0
            for (file in files) {
                val stack = ArrayDeque<PsiElement>()
                fun enterElement(element: PsiElement) {
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

                val visitor = if (file is PsiJavaFile) {
                    object : JavaRecursiveElementVisitor() {
                        override fun visitElement(element: PsiElement) {
                            enterElement(element)
                            super.visitElement(element)
                            exitElement(element)
                        }
                    }
                } else {
                    object : KtVisitorVoid() {
                        override fun visitElement(element: PsiElement) {
                            enterElement(element)
                            super.visitElement(element)
                            exitElement(element)
                        }
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
                val oriElement = PsiTreeUtil.findSameElementInCopy(element, oriFile)
                val oriLevel = fromElements[PsiWrapper.of(oriElement)]
                if (oriLevel != null) {
                    copiedElements[PsiWrapper.of(element)] = oriLevel
                }
                return
            }

            val visitor = if (copiedFile is PsiJavaFile) {
                object : JavaRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        enterElement(element)
                        super.visitElement(element)
                    }
                }
            } else {
                object : KtVisitorVoid() {
                    override fun visitElement(element: PsiElement) {
                        enterElement(element)
                        super.visitElement(element)
                    }
                }
            }
            visitor.visitElement(copiedFile)
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

    val JvmType.defaultValue: String
        get() = if (this is PsiType) {
            defaultValue
        } else {
            "(Void) null"
        }

    val PsiType.defaultValue: String
        get() = if (this is PsiClassType) {
            val clazz = resolve()
            if (clazz != null) {
                if (clazz.shouldBeDeleted()) {
                    "(Object) null"
                } else {
                    "(${clazz.qualifiedName!!}) null"
                }
            } else "(${canonicalText}) null"
        } else if (this is PsiPrimitiveType) when {
            this === PsiTypes.byteType() -> "java.lang.Byte.parseByte(\"1\")"
            this === PsiTypes.charType() -> "'1'"
            this === PsiTypes.doubleType() -> "1.0"
            this === PsiTypes.floatType() -> "1.0f"
            this === PsiTypes.intType() -> "1"
            this === PsiTypes.longType() -> "1L"
            this === PsiTypes.shortType() -> "java.lang.Short.parseShort(\"1\")"
            this === PsiTypes.booleanType() -> "true"
            this === PsiTypes.voidType() || this === PsiTypes.nullType() -> "null"
            else -> throw NoWhenBranchMatchedException()
        }
        else "(${canonicalText}) null"


    fun getDefaultValueTextForMethod(method: PsiMethod): String {
        val returnType = method.returnType
        if (returnType != null) {
            return returnType.defaultValue
        } else {
            val clazz = method.containingClass
            val defaultClassName = if (clazz != null) {
                if (!clazz.shouldBeDeleted()) {
                    clazz.qualifiedName ?: "Object"
                } else "Object"
            } else "Object"

            return "(${defaultClassName}) null"
        }
    }

    fun PsiElement.replaceOrDeleteParentStatement(createNewElement: () -> PsiElement) {
        val parent = parent
        if (parent is PsiExpressionStatement && parent.expression === this) {
            val newElement = createNewElement()
            if (newElement.canBeStatement()) {
                replace(newElement)
            } else {
                parent.delete()
            }
        } else {
            replace(createNewElement())
        }
    }

    fun createCallForConstructor(element: PsiMethod, context: PsiElement?): PsiElement {
        val parameters = element.parameters
        val parameterDefaultValues = mutableListOf<String>()
        for (parameter in parameters) {
            parameterDefaultValues.add(parameter.type.defaultValue)
        }
        val typeText = element.containingClass?.qualifiedName ?: "Object"
        return javaParserFacade.createExpressionFromText(
            "new ${typeText}(${parameterDefaultValues.joinToString(", ")})", context
        )
    }

    private fun editElementRef2Method(element: PsiElement, target: PsiMethod) {
        when {
            element.parent is PsiCallExpression -> {
                element.parent.replaceOrDeleteParentStatement {
                    javaParserFacade.createExpressionFromText(
                        getDefaultValueTextForMethod(target), element.parent.context
                    )
                }
            }

            element is PsiCall -> {
                if (target.isConstructor) {
                    // the constructor was deleted but we can still call another.
                    val clazz = target.containingClass
                    if (clazz != null && clazz.shouldBeDeleted()) {
                        return element.replaceOrDeleteParentStatement {
                            javaParserFacade.createExpressionFromText(
                                "new Object()", element.context
                            )
                        }
                    }
                    val resolveHelper = JavaPsiFacade.getInstance(project).resolveHelper
                    val accessibleConstructor = clazz?.constructors?.firstOrNull {
                        resolveHelper.isAccessible(it, element, null)
                                && !it.shouldBeDeleted()
                    }
                    if (accessibleConstructor != null) {
                        element.replaceOrDeleteParentStatement {
                            createCallForConstructor(accessibleConstructor, element.context)
                        }
                    } else if (clazz?.constructors?.all { it.shouldBeDeleted() } == true) {
                        // this means we can call default constructor
                        val classAccessible = resolveHelper.isAccessible(clazz, element, null)
                        if (classAccessible) {
                            element.replaceOrDeleteParentStatement {
                                javaParserFacade.createExpressionFromText(
                                    "new ${clazz.qualifiedName}()",
                                    element.context
                                )
                            }
                        } else {
                            element.replaceOrDeleteParentStatement {
                                javaParserFacade.createExpressionFromText(
                                    getDefaultValueTextForMethod(target),
                                    element.context
                                )
                            }
                        }
                    } else {
                        element.replaceOrDeleteParentStatement {
                            javaParserFacade.createExpressionFromText(
                                getDefaultValueTextForMethod(target),
                                element.context
                            )
                        }
                    }
                } else {
                    element.replaceOrDeleteParentStatement {
                        javaParserFacade.createExpressionFromText(getDefaultValueTextForMethod(target), element.context)
                    }
                }
            }
        }
    }

    private fun editElementRef2Variable(element: PsiElement, target: PsiVariable) {
        val parent = element.parent ?: return
        if (parent is PsiAssignmentExpression) {
            if (parent.lExpression === element) {
                val rExpr = parent.rExpression
                if (rExpr != null) {
                    parent.replaceOrDeleteParentStatement { rExpr }
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
        } else if (parent is PsiPostfixExpression || parent is PsiPrefixExpression) {
            editElementRef2Variable(parent, target)
        } else {
            element.replaceOrDeleteParentStatement {
                javaParserFacade.createExpressionFromText(
                    target.type.defaultValue, element.context
                )
            }
        }
    }

    fun editElement(element: PsiElement, target: PsiElement) {
        when (target) {
            is PsiClass -> editElementRef2Class(element, target)
            is PsiMethod -> editElementRef2Method(element, target)
            is PsiVariable -> editElementRef2Variable(element, target)
        }
        // todo other cases
    }

    fun deleteElement(element: PsiElement) {
        if (element !is PsiLocalVariable) {
            element.delete()
            return
        }
        val initializer = element.initializer
        if (initializer == null) {
            element.delete()
            return
        }
        if (initializer.canBeStatement()) {
            val statement = javaParserFacade.createStatementFromText("new Object();", element.context)
                    as PsiExpressionStatement
            statement.expression.replace(initializer)
            element.replace(statement)
        } else {
            element.delete()
        }
    }

    fun PsiElement?.shouldBeDeleted(shouldDelete: Set<PsiWrapper<*>> = emptySet()): Boolean {
        val wrapper = PsiWrapper.of(this ?: return false)
        if (wrapper in shouldDelete) return true
        return wrapper in elementsInOriProgram && wrapper !in elements
    }

    fun reconstructDependencies() {
        @Suppress("UNCHECKED_CAST")
        val files = elements.keys.filter { it.element is PsiFile }.map { it.element } as List<PsiFile>
        // we must resolve reference first. Otherwise, the reference will lose after delete.
        val needEdit = mutableListOf<Pair<PsiElement, PsiElement>>()
        val needDeleteElements = mutableSetOf<PsiElement>()

        for (file in files) {
            var shouldDeleteChildren = 0
            fun recordNeedDelete(element: PsiElement): Boolean {
                if (!element.isValid) return false
                if (!isDecl(element)) return false
                if (element.shouldBeDeleted() || shouldDeleteChildren > 0) {
                    elements.remove(PsiWrapper.of(element))
                    needDeleteElements.add(element)
                    return true
                }
                return false
            }

            val visitor = if (file is PsiJavaFile) {
                object : JavaRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        val needDelete = recordNeedDelete(element)
                        if (needDelete) shouldDeleteChildren++
                        super.visitElement(element)
                        if (needDelete) shouldDeleteChildren--
                    }
                }
            } else {
                object : KtVisitorVoid() {
                    override fun visitElement(element: PsiElement) {
                        val needDelete = recordNeedDelete(element)
                        if (needDelete) shouldDeleteChildren++
                        super.visitElement(element)
                        if (needDelete) shouldDeleteChildren--
                    }
                }
            }
            file.accept(visitor)
        }

        for (file in files) {
            fun recordNeedEdit(element: PsiElement) {
                val targets = element.references.mapNotNull { it.resolve() ?: return@mapNotNull null }
                val callTarget = if (element is PsiCall) {
                    val method = element.resolveMethod()
                    if (method != null && !method.shouldBeDeleted()) {
                        for ((i, param) in method.parameters.withIndex()) {
                            val sourceParam = param.sourceElement
                            if (sourceParam.shouldBeDeleted()) {
                                element.argumentList?.expressions[i]?.let { needDeleteElements.add(it) }
                            }
                        }
                    }
                    method
                } else null
                val allTargets = if (callTarget != null) targets + callTarget else targets
                for (target in allTargets) {
                    if (target.shouldBeDeleted()) {
                        needEdit.add(element to target)
                        break
                    }
                }
            }

            val visitor = if (file is PsiJavaFile) {
                object : JavaRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (!element.isValid) return
                        if (element in needDeleteElements) {
                            // we may replace initializer with the whole variable
                            // For example: Foo foo = bar.getFoo();
                            // So we must visit the initializer
                            if (element is PsiLocalVariable) {
                                element.initializer?.let { super.visitElement(it) }
                            }
                        }
                        recordNeedEdit(element)
                        super.visitElement(element)
                    }

                    override fun visitAssignmentExpression(expression: PsiAssignmentExpression) {
                        if (!expression.isValid) return
                        if (expression in needDeleteElements) return
                        // we need to edit right first because sometimes
                        // we may replace the whole assignment with the right
                        expression.rExpression?.accept(this)
                        expression.lExpression.accept(this)
                    }
                }
            } else {
                object : KtVisitorVoid() {
                    override fun visitElement(element: PsiElement) {
                        if (!element.isValid) return
                        recordNeedEdit(element)
                        super.visitElement(element)
                    }
                }
            }
            file.accept(visitor)
        }

        for ((element, target) in needEdit) {
            if (!element.isValid) continue
            editElement(element, target)
        }

        for (element in needDeleteElements) {
            if (!element.isValid) continue
            try {
                deleteElement(element)
            } catch (e: Exception) {
                e
            }
        }

        removeUnusedImport(files)
    }

    private fun removeUnusedImport(files: List<PsiFile>) {
        for (file in files) {
            // key: the imported element
            // value: the import statement
            val unusedImports = mutableMapOf<PsiWrapper<*>, PsiImportStatement>()
            val importLists = PsiTreeUtil.findChildrenOfType(file, PsiImportStatement::class.java)
            for (importList in importLists) {
                val imported = importList.resolve() ?: continue
                unusedImports[PsiWrapper.of(imported)] = importList
            }

            val visitor = if (file is PsiJavaFile) {
                object : JavaRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (element is PsiImportList) {
                            return
                        }

                        val targets = element.references.mapNotNull { it.resolve() ?: return@mapNotNull null }
                        for (target in targets) {
                            unusedImports.remove(PsiWrapper.of(target))
                        }

                        super.visitElement(element)
                    }
                }
            } else {
                object : KtVisitorVoid() {
                    override fun visitElement(element: PsiElement) {
                        TODO()
                    }
                }
            }
            file.accept(visitor)
            for ((_, importStatement) in unusedImports) {
                importStatement.delete()
            }
        }

    }
}