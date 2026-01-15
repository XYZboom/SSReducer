package io.github.xyzboom.ssreducer.kotlin

import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

class MockCodeStyle : LanguageCodeStyleSettingsProvider() {
    override fun getCodeSample(settingsType: SettingsType): String {
        return ""
    }

    override fun getLanguage(): Language {
        return JavaLanguage.INSTANCE
    }

}