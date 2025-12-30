package io.github.xyzboom.ssreducer

import com.intellij.configurationStore.SchemeNameToFileName
import com.intellij.configurationStore.StreamProvider
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.options.EmptySchemesManager
import com.intellij.openapi.options.Scheme
import com.intellij.openapi.options.SchemeManager
import com.intellij.openapi.options.SchemeManagerFactory
import com.intellij.openapi.options.SchemeProcessor
import java.nio.file.Path

class MockSchemeManagerFactory: SchemeManagerFactory() {
    override fun <SCHEME : Scheme, MUTABLE_SCHEME : SCHEME> create(
        directoryName: String,
        processor: SchemeProcessor<SCHEME, MUTABLE_SCHEME>,
        presentableName: String?,
        roamingType: RoamingType,
        schemeNameToFileName: SchemeNameToFileName,
        streamProvider: StreamProvider?,
        directoryPath: Path?,
        isAutoSave: Boolean,
        settingsCategory: SettingsCategory
    ): SchemeManager<SCHEME> {
        return EmptySchemesManager() as SchemeManager<SCHEME>
    }
}