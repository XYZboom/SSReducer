package io.github.xyzboom.ssreducer

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.lang.ref.WeakReference
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class PsiWrapper<out T : PsiElement> private constructor(val element: T) {
    companion object {
        val ID_KEY = Key.create<Long>("UUID_KEY")
        private val currentId = AtomicLong(0)
        val WRAPPER_KEY = Key.create<WeakReference<PsiWrapper<*>>>("WRAPPER_KEY")
        fun <T : PsiElement> of(element: T): PsiWrapper<T> {
            val wrapper = element.getUserData(WRAPPER_KEY)?.get()
            if (wrapper != null) {
                @Suppress("UNCHECKED_CAST")
                return wrapper as PsiWrapper<T>
            }
            val createNewWrapper = PsiWrapper(element)
            element.putUserData(WRAPPER_KEY, WeakReference(createNewWrapper))
            val oriId = element.getCopyableUserData(ID_KEY)
            if (oriId == null) {
                element.putCopyableUserData(ID_KEY, currentId.addAndFetch(1))
            }
            return createNewWrapper
        }
    }

    private val manager = PsiManager.getInstance(element.project)

    val id: Long?
        get() = element.getCopyableUserData(ID_KEY)

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PsiWrapper<*> && other !is PsiElement) {
            return false
        }
        if (other is PsiWrapper<*>) {
            val myId = id
            val otherId = other.id
            if (myId != null || otherId != null) {
                return myId == otherId
            }
            return manager.areElementsEquivalent(this.element, other.element)
        }
        other as PsiElement
        val myId = id
        val otherId = other.getCopyableUserData(ID_KEY)
        if (myId != null || otherId != null) {
            return myId == otherId
        }
        return manager.areElementsEquivalent(this.element, other)
    }

    override fun toString(): String {
        return "$id $element"
    }
}