package io.github.xyzboom.ssreducer.cpp

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.cidr.lang.psi.*
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import com.jetbrains.cidr.lang.symbols.OCResolveContext
import com.jetbrains.cidr.lang.symbols.OCSymbol
import com.jetbrains.cidr.lang.types.OCArrayType
import com.jetbrains.cidr.lang.types.OCFunctionType
import com.jetbrains.cidr.lang.types.OCIntType
import com.jetbrains.cidr.lang.types.OCPointerType
import com.jetbrains.cidr.lang.types.OCReferenceType
import com.jetbrains.cidr.lang.types.OCStructType
import com.jetbrains.cidr.lang.types.OCType
import com.jetbrains.cidr.lang.types.visitors.OCArrayToPointerChanger
import com.jetbrains.cidr.lang.util.OCElementFactory
import io.github.xyzboom.ssreducer.PsiWrapper
import io.github.xyzboom.ssreducer.parentOfTypeAndDirectChild
import kotlin.math.max
import com.intellij.psi.util.hasErrorElementInRange

class GroupElements(
    val project: Project,
    val elements: MutableMap<PsiWrapper<*>, Int>,
    val elementsInOriProgram: MutableSet<PsiWrapper<*>>,
    var maxLevel: Int,
) {

    private val allScope = GlobalSearchScope.allScope(project)

    companion object {

        private val REF_TARGET_KEY = Key.create<List<PsiWrapper<*>>>("REF_TARGET_KEY")

        /**
         * Consider the declaration and definition pair such as:
         * ```cpp
         * void foo();
         * // ... usage of foo
         * void foo() {}
         * ```
         * When we add declaration `void foo();` into group, the definition is no need to add.
         */
        private val DECL_OF_DEF_KEY = Key.create<PsiWrapper<*>>("DECL_OF_DEF_KEY")
        private val DEF_OF_DECL_KEY = Key.create<PsiWrapper<*>>("DEF_OF_DECL_KEY")

        private val TYPE_DEFAULT_VALUE_KEY = Key.create<PsiElement>("TYPE_DEFAULT_VALUE_KEY")
        private val TYPE_AND_CONTEXT_KEY = Key.create<Pair<OCType, PsiElement>>("TYPE_AND_CONTEXT_KEY")

        fun preprocess(project: Project, files: Collection<OCFile>) {
            for (file in files) {
                file.accept(object : OCRecursiveVisitor() {
                    override fun visitElement(element: PsiElement) {
                        super.visitElement(element)
                        PsiWrapper.of(element)
                        if (element is OCSymbolDeclarator<*>) {
                            val symbol = element.symbol
                            if (symbol != null) {
                                val defSymbol = symbol.getDefinitionSymbol(project)
                                if (defSymbol != null) {
                                    val def = defSymbol.locateDefinition(project)
                                    val isAncestor = def?.let {
                                        PsiTreeUtil.isAncestor(element, it, false)
                                    } ?: true
                                    // !isAncestor means the definition is not the current element
                                    if (!isAncestor) {
                                        val defParent = def.parent!!
                                        element.putCopyableUserData(DEF_OF_DECL_KEY, PsiWrapper.of(defParent))
                                        defParent.putCopyableUserData(DECL_OF_DEF_KEY, PsiWrapper.of(element))
                                    }
                                }
                            }
                        }
                        if (element is OCExpression) {
                            element.putCopyableUserData(TYPE_AND_CONTEXT_KEY, element.resolvedType to element.context!!)
                        }
                    }
                })
            }
        }

        fun isTypedef(element: PsiElement): Boolean {
            return element is OCDeclaration && element.isTypedef
        }

        fun isDecl(element: PsiElement): Boolean {
            return element is OCDeclaration ||
                    element is OCFile ||
                    element is OCStatement
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
                        method
                    } else null
                    val allTargets = if (callTarget != null) targets + callTarget else targets
                    element.putCopyableUserData(REF_TARGET_KEY, allTargets.map { PsiWrapper.of(it) })
                    if (element.parent is OCReferenceExpression) {
                        element.parent.putCopyableUserData(REF_TARGET_KEY, allTargets.map { PsiWrapper.of(it) })
                    }
                    if (isDecl(element)) {
                        stack.addFirst(element)
                        // If no decl of current element found, we record current element.
                        // Otherwise, we can say the decl is already recorded.
                        if (element.getCopyableUserData(DECL_OF_DEF_KEY) == null) {
                            elements[PsiWrapper.of(element)] = stack.size
                        }
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
            project, HashMap(elements), HashSet(elements.keys), maxLevel
        )
    }

    fun copyOf(fromElements: Map<PsiWrapper<*>, Int>): GroupElements {
        val editedFrom = elements.keys.intersect(fromElements.keys)

        @Suppress("UNCHECKED_CAST")
        val files = editedFrom.filter { it.element is PsiFile }.map { it.element } as List<PsiFile>
        val copiedFiles = files.map { it.copy() as PsiFile }
        val copiedElements = mutableMapOf<PsiWrapper<*>, Int>()
        for (copiedFile in copiedFiles) {
            fun enterElement(element: PsiElement) {
                if (!isDecl(element)) {
                    return
                }
                val wrapper = PsiWrapper.of(element)
                if (wrapper !in editedFrom) {
                    return
                }
                val oriLevel = fromElements[wrapper]
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
            project, copiedElements, LinkedHashSet(elementsInOriProgram), maxLevel
        )
    }

    fun fileContents(): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val files = elements.keys.filter { it.element is PsiFile }.map { it.element } as List<PsiFile>
        return files.associate { it.originalFile.virtualFile.path to it.text!! }
    }

    private fun editTypeRef(element: PsiElement, target: PsiElement) {
        val voidPointer = OCElementFactory.typeElementFromText("void***", element.context!!)
        element.replace(voidPointer)
    }

    private fun OCSymbol.definitionShouldBeDeleted(): Boolean {
        val def = locateDefinition(project)
        return def.shouldBeDeleted()
    }

    private fun OCReferenceType.shouldBeDeleted(ocContext: PsiElement): Boolean {
        val resolveContext = OCResolveContext.forPsi(ocContext)
        val symbols = this.reference.resolveToSymbols(resolveContext)
        for (symbol in symbols) {
            if (symbol.definitionShouldBeDeleted()) return true
        }
        return false
    }

    private fun OCType.shouldBeDeleted(ocContext: PsiElement): Boolean {
        return when (this) {
            is OCReferenceType -> shouldBeDeleted(ocContext)
            is OCStructType -> symbol.definitionShouldBeDeleted()
            else -> false // todo
        }
    }

    fun PsiElement.replaceOrDeleteParentStatement(createNewElement: () -> PsiElement) {
        val parent = parent
        if (parent is OCExpressionStatement && parent.expression === this) {
            parent.delete()
        } else {
            if (parent is OCAssignmentExpression) {
                val parentSourceExpr = parent.sourceExpression
                if (parent.receiverExpression === this && parentSourceExpr != null) {
                    parent.replace(parentSourceExpr)
                    return
                }
            } else if (parent is OCPrefixExpression || parent is OCPostfixExpression) {
                parent.replaceOrDeleteParentStatement {
                    createNewElement()
                }
                return
            } else if (parent is OCReferenceExpression) {
                parent.replaceOrDeleteParentStatement {
                    createNewElement()
                }
                return
            }
            replace(createNewElement())
        }
    }

    private fun editCallExpr(element: OCCallExpression, target: PsiElement) {
        if (target is OCDeclarator) {
            when (val targetParent = target.parent) {
                is OCFunctionDeclaration -> {
                    val returnType = targetParent.returnType
                    element.replaceOrDeleteParentStatement {
                        if (returnType.shouldBeDeleted(targetParent)) {
                            val pointerDepth = returnType.pointersDepth()
                            val extra = "*".repeat(pointerDepth)
                            OCElementFactory.expressionFromText("((void***${extra})0)", element.context!!)!!
                        } else {
                            val defaultValue = element.getUserData(TYPE_DEFAULT_VALUE_KEY)
                            defaultValue ?: createDefaultValueExprForType(returnType, element)
                        }
                    }
                }
                else -> reportMissedEdit(element, target)
            }
        } else {
            reportMissedEdit(element, target)
        }
    }

    private fun extractCall(element: OCCallExpression) {
        val arguments = element.arguments
        val pair = element.parentOfTypeAndDirectChild<OCBlockStatement>()
        if (pair != null) {
            val (block, insertPos) = pair
            val context = element.context!!
            for (argument in arguments) {
                val statement = OCElementFactory.statementFromText("0;", context) as OCExpressionStatement
                statement.expression.replace(argument)
                block.addBefore(OCElementFactory.newlineFromText(context), insertPos)
                val addedStatement = block.addBefore(statement, insertPos)
                val statementWrapper = PsiWrapper.of(addedStatement)
                elementsInOriProgram.add(statementWrapper)
                if (!elements.contains(statementWrapper)) {
                    elements[statementWrapper] = maxLevel + 1
                }
            }
        }
    }

    private fun OCType.nameOrDeletedName(ocContext: PsiElement): String {
        if (shouldBeDeleted(ocContext)) {
            return "void ***"
        }
        if (this is OCArrayType) {
            return OCArrayToPointerChanger.INSTANCE.visitArrayType(this).toPointerName(ocContext)
        }
        if (this is OCFunctionType) {
            val returnTypeName = returnType.nameOrDeletedName(ocContext)
            val paramsName = parameterTypes.joinToString(", ") { it.nameOrDeletedName(ocContext) }
            return "${returnTypeName}($paramsName)"
        }
        if (this is OCPointerType) {
            val pointerTo = refType.nameOrDeletedName(ocContext)
            return "${pointerTo}${"*".repeat(pointersDepth())}"
        }
        if (this is OCStructType) {
            return "struct $name"
        }
        return name
    }

    private fun OCType.unwrapPointer(): OCType {
        if (this is OCPointerType) {
            return this.refType.unwrapPointer()
        }
        return this
    }

    private fun OCType.toPointerName(ocContext: PsiElement): String {
        if (this.shouldBeDeleted(ocContext)) {
            return "void ***"
        }
        if (this is OCArrayType) {
            return OCArrayToPointerChanger.INSTANCE.visitArrayType(this).toPointerName(ocContext)
        }
        if (this is OCFunctionType) {
            val returnTypeName = returnType.nameOrDeletedName(ocContext)
            val paramsName = parameterTypes.joinToString(", ") { it.nameOrDeletedName(ocContext) }
            return "${returnTypeName}(*)($paramsName)"
        }
        if (this is OCPointerType) {
            val pointerTo = refType.unwrapPointer().nameOrDeletedName(ocContext)
            return "${pointerTo}${"*".repeat(pointersDepth())}*"
        }
        if (this is OCStructType) {
            return "struct $name*"
        }
        return "$name*"
    }

    private fun createDefaultValueExprForType(type: OCType, context: PsiElement): OCExpression {
        if (type is OCIntType && !type.shouldBeDeleted(context)) {
            return OCElementFactory.expressionFromText("((${type.name})0)", context)!!
        }
        return OCElementFactory.expressionFromText("(*((${type.toPointerName(context)})0))", context)!!
    }

    private fun editReference(element: OCReferenceElement, target: PsiElement) {
        when (val parent = element.parent) {
            is OCTypeElement -> editTypeRef(element, target)
            is OCStruct -> when (val parent2 = parent.parent) {
                is OCTypeElement -> editTypeRef(parent2, target)
                else -> reportMissedEdit(element, target)
            }
            is OCReferenceExpression -> {
                if (parent.children.size == 1 && parent.children.single() === element) {
                    editExpr(parent, target)
                } else {
                    reportMissedEdit(element, target)
                }
            }

            is OCGotoStatement -> {
                parent.delete()
            }

            else -> reportMissedEdit(element, target)
        }
    }

    private fun shouldEditParentInstead(element: OCExpression, parent: OCExpression): Boolean {
        return when (parent) {
            is OCArraySelectionExpression -> {
                parent.arrayExpression === element
            }
            is OCCallExpression -> {
                parent.functionReferenceExpression === element
            }
            else -> false
        }
    }

    private fun editExpr(element: OCExpression, target: PsiElement) {
        val parent = element.parent
        when (parent) {
            is OCCallExpression -> {
                extractCall(parent)
            }

            is OCAssignmentExpression if parent.receiverExpression === parent -> {
                parent.sourceExpression?.let { parent.replace(it) }
            }
        }
        if (parent is OCExpression && shouldEditParentInstead(element, parent)) {
            editExpr(parent, target)
        } else {
            element.replaceOrDeleteParentStatement {
                val defaultValue = element.getUserData(TYPE_DEFAULT_VALUE_KEY)
                defaultValue ?: OCElementFactory.expressionFromText("((void***)0)", element.context!!)!!
            }
        }
    }

    private fun reportMissedEdit(element: PsiElement, target: PsiElement) {
        println("missing edit at: ${element.text}")
    }

    private fun reportKnowMissedEdit(element: PsiElement, target: PsiElement) {
        // pass
    }

    private fun editElement(element: PsiElement, target: PsiElement) {
        when (element) {
            is OCReferenceElement -> editReference(element, target)
            is OCExpression -> editExpr(element, target)
            is OCDeclarator -> {
                reportKnowMissedEdit(element, target)
            }

            else -> reportMissedEdit(element, target)
        }
    }

    private fun deleteElement(element: PsiElement) {
        if (element !is OCExpression) {
            element.delete()
        } else {
            val context = element.context!!
            element.replaceOrDeleteParentStatement {
                val defaultValue = element.getUserData(TYPE_DEFAULT_VALUE_KEY)
                defaultValue ?: createDefaultValueExprForType(element.resolvedType, context)
            }
        }
    }

    fun PsiElement?.strictShouldBeDeleted(shouldDelete: Set<PsiWrapper<*>> = emptySet()): Boolean {
        val wrapper = PsiWrapper.of(this ?: return false)
        if (wrapper in shouldDelete) return true
        val declOfDef = wrapper.element.getCopyableUserData(DECL_OF_DEF_KEY)
        return (declOfDef != null && declOfDef.element.shouldBeDeleted(shouldDelete))
                || (wrapper in elementsInOriProgram && wrapper !in elements)
    }

    fun PsiElement?.shouldBeDeleted(shouldDelete: Set<PsiWrapper<*>> = emptySet()): Boolean {
        this ?: return false
        val parent = parent
        if (this is OCDeclarator) {
            if (parent.strictShouldBeDeleted(shouldDelete)) {
                return true
            }
        }
        val parent2 = parent?.parent
        if (this is OCStruct && parent is OCTypeElement && parent2 is OCDeclaration) {
            if (parent2.strictShouldBeDeleted(shouldDelete)) {
                return true
            }
        }
        return strictShouldBeDeleted(shouldDelete)
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
                if (element is OCExpression) {
                    val typeAndContext = element.getCopyableUserData(TYPE_AND_CONTEXT_KEY)
                    if (typeAndContext != null) {
                        val (resolvedType, context) = typeAndContext
                        val defaultValue = createDefaultValueExprForType(resolvedType, context)
                        element.putUserData(TYPE_DEFAULT_VALUE_KEY, defaultValue)
                    }
                    if (element is OCCallExpression) {
                        val methods =
                            element.functionReferenceExpression.getCopyableUserData(REF_TARGET_KEY) ?: emptyList()
                        for (methodWrapper in methods) {
                            val method = methodWrapper.element
                            if (method is OCFunctionDeclaration && !method.shouldBeDeleted()) {
                                for ((i, param) in (method.parameters ?: emptyList()).withIndex()) {
                                    if (param.shouldBeDeleted()) {
                                        needDeleteElements.add(PsiWrapper.of(element.arguments[i]))
                                    }
                                }
                            }
                        }
                    }
                }
                for (targetWrapper in allTargets) {
                    val target = targetWrapper.element
                    if (target.shouldBeDeleted()) {
                        needEdit.add(element to target)
                        break
                    }
                }
            }

            val visitor = object : OCReverseDFSVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (!element.isValid) return
                    super.visitElement(element)
                    if (PsiWrapper.of(element) in needDeleteElements) {
                        // we may replace initializer with the whole variable
                        // For example: Foo foo = bar.getFoo();
                        // So we must visit the initializer
//                        if (element is PsiLocalVariable) {
//                            element.initializer?.let { super.visitElement(it) }
//                        }
                    }
                    recordNeedEdit(element)
                }
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

        maxLevel = elements.values.maxOrNull() ?: 1
    }
}