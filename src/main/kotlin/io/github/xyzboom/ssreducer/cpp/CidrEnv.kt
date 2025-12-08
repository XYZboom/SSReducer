package io.github.xyzboom.ssreducer.cpp

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.mock.MockDumbService
import com.intellij.mock.MockProject
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.LanguageInjector
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.psi.impl.PsiManagerImpl
import com.intellij.psi.impl.PsiModificationTrackerImpl
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageManagerImpl
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.CachedValuesManagerImpl
import com.jetbrains.cidr.lang.OCFileType
import com.jetbrains.cidr.lang.OCLanguage
import com.jetbrains.cidr.lang.parser.OCParserDefinition
import com.jetbrains.cidr.lang.preprocessor.OCHeaderContextCache
import com.jetbrains.cidr.lang.preprocessor.OCInclusionContextLazyGetDefinitionProvider
import com.jetbrains.cidr.lang.preprocessor.OCResolveRootAndConfigurationCache
import com.jetbrains.cidr.lang.preprocessor.OCResolveRootAndConfigurationProvider
import com.jetbrains.cidr.lang.settings.OCResolveContextSettings
import com.jetbrains.cidr.lang.symbols.symtable.EP_NAME
import com.jetbrains.cidr.lang.symbols.symtable.FileSymbolTableProvider
import com.jetbrains.cidr.lang.symbols.symtable.FileSymbolTablesCache
import com.jetbrains.cidr.lang.symbols.symtable.OCBuildSymbolsVetoExtension
import com.jetbrains.cidr.lang.symbols.symtable.OCSymbolTableProvider
import com.jetbrains.cidr.lang.workspace.OCLanguageKindCalculatorHelper
import com.jetbrains.cidr.lang.workspace.OCWorkspace
import com.jetbrains.cidr.lang.workspace.OCWorkspaceImpl
import com.jetbrains.cidr.lang.workspace.OCWorkspaceRunConfigurationListener
import org.jetbrains.kotlin.cli.jvm.compiler.setupIdeaStandaloneExecution

class CidrEnv {
    init {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true")
        }
        System.setProperty("idea.platform.prefix", "CLion")
        setupIdeaStandaloneExecution()
    }

    @Suppress("UnstableApiUsage")
    fun init(): Pair<CoreApplicationEnvironment, Project> {
        val appEnv = CoreApplicationEnvironment(Disposer.newDisposable())
        val project = MockProject(appEnv.application.picoContainer, appEnv.parentDisposable)
        val appExtArea = appEnv.application.extensionArea
        val projectExtArea = project.extensionArea
        project.registerService(PsiManager::class.java, PsiManagerImpl::class.java)
        project.registerService(PsiFileFactory::class.java, PsiFileFactoryImpl::class.java)
        CoreApplicationEnvironment.registerExtensionPoint(
            projectExtArea, MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME.name, MultiHostInjector::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            appExtArea, LanguageInjector.EXTENSION_POINT_NAME, LanguageInjector::class.java
        )

        project.registerService(InjectedLanguageManager::class.java, InjectedLanguageManagerImpl::class.java)
        project.registerService(DumbService::class.java, MockDumbService::class.java)
        project.registerService(
            OCResolveRootAndConfigurationCache::class.java,
            OCResolveRootAndConfigurationCache::class.java
        )
        project.registerService(
            OCHeaderContextCache::class.java,
            OCHeaderContextCache::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            appExtArea, EP_NAME, FileSymbolTableProvider::class.java
        )
        appExtArea.getExtensionPoint(EP_NAME)
            .registerExtension(OCSymbolTableProvider(), appEnv.parentDisposable)
        CoreApplicationEnvironment.registerExtensionPoint(
            appExtArea, OCLanguageKindCalculatorHelper.EP_NAME, OCLanguageKindCalculatorHelper::class.java
        )
        project.registerService(
            CachedValuesManager::class.java,
            CachedValuesManagerImpl::class.java
        )
        project.registerService(
            OCWorkspace::class.java,
            OCWorkspaceImpl::class.java
        )
        project.registerService(
            OCResolveContextSettings::class.java,
            OCResolveContextSettings::class.java
        )
        project.registerService(
            OCWorkspaceRunConfigurationListener::class.java,
            OCWorkspaceRunConfigurationListener::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            appExtArea, OCInclusionContextLazyGetDefinitionProvider.EP_NAME,
            OCInclusionContextLazyGetDefinitionProvider::class.java
        )
        val extensions = listOf("cpp", "c", "h", "m", "mm")
        for (extension in extensions) {
            appEnv.registerFileType(OCFileType.INSTANCE, extension)
        }
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(
            OCLanguage.getInstance(),
            OCParserDefinition()
        )
        project.registerService(
            ResolveCache::class.java,
            ResolveCache::class.java
        )
        project.registerService(
            FileSymbolTablesCache::class.java,
            FileSymbolTablesCache::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            appExtArea, OCBuildSymbolsVetoExtension.EP_NAME,
            OCBuildSymbolsVetoExtension::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            appExtArea, OCResolveRootAndConfigurationProvider.EP_NAME,
            OCResolveRootAndConfigurationProvider::class.java
        )
        project.registerService(
            PsiModificationTracker::class.java,
            PsiModificationTrackerImpl::class.java
        )
        return appEnv to project
    }
}