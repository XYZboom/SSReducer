package io.github.xyzboom.ssreducer.kotlin

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PsiWrapper(val element: PsiElement, putUuid: Boolean = false) {
    companion object {
        val UUID_KEY = Key.create<Uuid>("UUID_KEY")
    }

    private val manager = PsiManager.getInstance(element.project)

    init {
        if (putUuid && uuid == null) {
            element.putCopyableUserData(UUID_KEY, Uuid.random())
        }
    }

    private val uuid: Uuid?
        get() = element.getCopyableUserData(UUID_KEY)

    override fun hashCode(): Int {
        return uuid.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PsiWrapper && other !is PsiElement) {
            return false
        }
        if (other is PsiWrapper) {
            val myUuid = uuid
            val otherUuid = other.uuid
            if (myUuid != null || otherUuid != null) {
                return myUuid == otherUuid
            }
            return manager.areElementsEquivalent(this.element, other.element)
        }
        other as PsiElement
        val myUuid = uuid
        val otherUuid = other.getCopyableUserData(UUID_KEY)
        if (myUuid != null || otherUuid != null) {
            return myUuid == otherUuid
        }
        return manager.areElementsEquivalent(this.element, other)
    }

    override fun toString(): String {
        return element.toString()
    }
}