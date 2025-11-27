package io.github.xyzboom.ssreducer.kotlin

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.lang.ref.WeakReference
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PsiWrapper private constructor(val element: PsiElement) {
    companion object {
        val UUID_KEY = Key.create<Uuid>("UUID_KEY")
        val WRAPPER_KEY = Key.create<WeakReference<PsiWrapper>>("WRAPPER_KEY")
        fun of(element: PsiElement): PsiWrapper {
            val wrapper = element.getUserData(WRAPPER_KEY)?.get()
            if (wrapper != null) {
                return wrapper
            }
            val createNewWrapper = PsiWrapper(element)
            element.putUserData(WRAPPER_KEY, WeakReference(createNewWrapper))
            val oriUuid = element.getCopyableUserData(UUID_KEY)
            if (oriUuid == null) {
                element.putCopyableUserData(UUID_KEY, Uuid.random())
            }
            return createNewWrapper
        }
    }

    private val manager = PsiManager.getInstance(element.project)

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