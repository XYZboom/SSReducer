package io.github.xyzboom.ssreducer.cpp

import com.intellij.idea.main
import com.intellij.psi.PsiFileFactory
import com.jetbrains.cidr.lang.OCLanguage
import com.jetbrains.cidr.lang.resolve.references.kvc.KeyReferenceProvider
import io.github.xyzboom.ssreducer.IReducer

class CppSSReducer : IReducer {
    override fun doReduce(args: Array<String>) {
        val cidrEnv = CidrEnv()
        val (appEnv, project) = cidrEnv.init()
        val factory = PsiFileFactory.getInstance(project)
        val fileA = """
            int func(int a) {
                return a;
            }
        """.trimIndent()
        val file = factory.createFileFromText(OCLanguage.getInstance(), fileA)
        main(emptyArray())
        val ref = file.children[0].children[2].children[2].children[0].children[0].references[0]
        ref.resolve()
        println(file)
    }
}