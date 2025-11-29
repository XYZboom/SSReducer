package io.github.xyzboom.ssreducer.kotlin

import com.intellij.lang.jvm.types.JvmType
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
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
    private val javaCodeStyleManager: JavaCodeStyleManager = project.getService(JavaCodeStyleManager::class.java)

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

    fun createTypeDefaultValueElement(containingFile: PsiJavaFile, type: JvmType, context: PsiElement?): PsiExpression {
        return when (type) {
            is PsiClassType -> {
                val clazz = type.resolve()
                val valueExpr = javaParserFacade.createExpressionFromText("(Object) null", context)
                if (clazz != null) {
                    if (!clazz.shouldBeDeleted()) {
                        val ref = javaParserFacade.createReferenceElementByType(type)
                        // the element "Object" in "(Object) null"
                        val ref2Obj = PsiTreeUtil.findChildOfType(valueExpr, PsiJavaCodeReferenceElement::class.java)
                        ref2Obj!!.replace(ref)
                        javaCodeStyleManager.addImport(containingFile, clazz)
                    }
                } else {
                    val ref = javaParserFacade.createReferenceElementByType(type)
                    val ref2Obj = PsiTreeUtil.findChildOfType(valueExpr, PsiJavaCodeReferenceElement::class.java)
                    ref2Obj!!.replace(ref)
                }
                valueExpr
            }

            is PsiPrimitiveType -> {
                val text = when {
                    type === PsiTypes.byteType() -> "Byte.parseByte(\"1\")"
                    type === PsiTypes.charType() -> "'1'"
                    type === PsiTypes.doubleType() -> "1.0"
                    type === PsiTypes.floatType() -> "1.0f"
                    type === PsiTypes.intType() -> "1"
                    type === PsiTypes.longType() -> "1L"
                    type === PsiTypes.shortType() -> "Short.parseShort(\"1\")"
                    type === PsiTypes.booleanType() -> "true"
                    type === PsiTypes.voidType() || type === PsiTypes.nullType() -> "null"
                    else -> throw NoWhenBranchMatchedException()
                }
                javaParserFacade.createExpressionFromText(text, context)
            }

            is PsiType -> {
                // todo handle other cases
                javaParserFacade.createExpressionFromText("(${type.canonicalText}) null", context)
            }

            else -> {
                javaParserFacade.createExpressionFromText("(Void) null", context)
            }
        }
    }

    fun createDefaultValueExprForClass(
        containingFile: PsiJavaFile, clazz: PsiClass?, context: PsiElement?
    ): PsiExpression {
        val valueExpr = javaParserFacade.createExpressionFromText("(Object) null", context)
        if (clazz != null) {
            if (!clazz.shouldBeDeleted()) {
                val ref = javaParserFacade.createClassReferenceElement(clazz)
                // the element "Object" in "(Object) null"
                val ref2Obj = PsiTreeUtil.findChildOfType(valueExpr, PsiJavaCodeReferenceElement::class.java)
                ref2Obj!!.replace(ref)
                javaCodeStyleManager.addImport(containingFile, clazz)
            }
        }

        return valueExpr
    }

    fun createDefaultValueExprForMethod(
        containingFile: PsiJavaFile, method: PsiMethod, context: PsiElement?
    ): PsiExpression {
        val returnType = method.returnType
        if (returnType != null) {
            return createTypeDefaultValueElement(
                containingFile, returnType, context
            )
        } else {
            val clazz = method.containingClass
            return createDefaultValueExprForClass(containingFile, clazz, context)
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

    fun createCallForConstructor(containingFile: PsiJavaFile, element: PsiMethod, context: PsiElement?): PsiElement {
        val parameters = element.parameters
        val placeholderArgsText = Array(parameters.size) { "null" }.joinToString(", ")
        val placeholderText = "new Object($placeholderArgsText)"
        val placeholder = javaParserFacade.createExpressionFromText(placeholderText, context)
        val ref2Obj = PsiTreeUtil.findChildOfType(placeholder, PsiJavaCodeReferenceElement::class.java)
        if (parameters.isNotEmpty()) {
            val placeholderArgs = PsiTreeUtil.getChildOfType(placeholder, PsiExpressionList::class.java)!!
            for ((i, placeholderArg) in placeholderArgs.expressions.withIndex()) {
                placeholderArg.replace(createTypeDefaultValueElement(containingFile, parameters[i].type, context))
            }
        }
        // we find this PsiMethod (actually a constructor) from a class,
        // so we ensure that element.containingClass is not null
        val ref2Class = javaParserFacade.createClassReferenceElement(element.containingClass!!)
        ref2Obj!!.replace(ref2Class)
        return placeholder
    }

    private fun editElementRef2Method(element: PsiElement, target: PsiMethod) {
        when {
            element.parent is PsiCallExpression -> {
                element.parent.replaceOrDeleteParentStatement {
                    createDefaultValueExprForMethod(
                        element.containingFile as PsiJavaFile, target, element.parent.context
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
                            createCallForConstructor(
                                element.containingFile as PsiJavaFile, accessibleConstructor, element.context
                            )
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
                                createDefaultValueExprForMethod(
                                    element.containingFile as PsiJavaFile, target, element.context
                                )
                            }
                        }
                    } else {
                        element.replaceOrDeleteParentStatement {
                            createDefaultValueExprForMethod(
                                element.containingFile as PsiJavaFile, target, element.context
                            )
                        }
                    }
                } else {
                    element.replaceOrDeleteParentStatement {
                        createDefaultValueExprForMethod(
                            element.containingFile as PsiJavaFile, target, element.context
                        )
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
                        createTypeDefaultValueElement(
                            element.containingFile as PsiJavaFile, target.type, parent.context
                        )
                    }
                }
            } else {
                element.replaceOrDeleteParentStatement {
                    createTypeDefaultValueElement(
                        element.containingFile as PsiJavaFile, target.type, element.context
                    )
                }
            }
        } else if (parent is PsiPostfixExpression || parent is PsiPrefixExpression) {
            editElementRef2Variable(parent, target)
        } else {
            element.replaceOrDeleteParentStatement {
                createTypeDefaultValueElement(
                    element.containingFile as PsiJavaFile, target.type, element.context
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

        for (file in files) {
            if (file is PsiJavaFile) {
                javaCodeStyleManager.shortenClassReferences(file)
                javaCodeStyleManager.optimizeImports(file)
            }
        }
    }
}