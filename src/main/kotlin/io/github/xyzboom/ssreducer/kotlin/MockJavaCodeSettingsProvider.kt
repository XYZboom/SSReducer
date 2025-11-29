package io.github.xyzboom.ssreducer.kotlin

import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.codeStyle.*
import javax.swing.JComponent

/**
 * Avoid missing class in [com.intellij.ide.JavaLanguageCodeStyleSettingsProvider]
 */
class MockJavaCodeSettingsProvider: LanguageCodeStyleSettingsProvider() {
    override fun getCodeSample(settingsType: SettingsType): String {
        return MockJavaCodeSettingsProvider::class.qualifiedName!!
    }

    override fun getLanguage(): Language {
        return JavaLanguage.INSTANCE
    }

    override fun createCustomSettings(settings: CodeStyleSettings): CustomCodeStyleSettings {
        return JavaCodeStyleSettings(settings)
    }

    override fun createConfigurable(
        baseSettings: CodeStyleSettings,
        modelSettings: CodeStyleSettings
    ): CodeStyleConfigurable {
        return object : CodeStyleConfigurable {
            override fun reset(settings: CodeStyleSettings) {
            }

            override fun apply(settings: CodeStyleSettings) {
            }

            override fun createComponent(): JComponent? {
                return null
            }

            override fun isModified(): Boolean {
                return false
            }

            override fun apply() {
            }

            override fun getDisplayName(): @NlsContexts.ConfigurableName String {
                return "MockJavaCode"
            }

        }
    }
}