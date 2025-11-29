/*
 * Copyright 2022 Google LLC
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.xyzboom.ssreducer

import com.intellij.application.options.codeStyle.cache.CodeStyleCachingService
import com.intellij.application.options.codeStyle.cache.CodeStyleCachingServiceImpl
import com.intellij.codeInsight.ImportFilter
import com.intellij.core.CoreApplicationEnvironment
import com.intellij.formatting.ExcludedFileFormattingRestriction
import com.intellij.formatting.Formatter
import com.intellij.formatting.FormatterImpl
import com.intellij.formatting.service.CoreFormattingService
import com.intellij.formatting.service.FormattingService
import com.intellij.lang.LanguageFormattingRestriction
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.CoroutineSupport
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.options.SchemeManagerFactory
import com.intellij.openapi.util.Disposer
import com.intellij.pom.PomModel
import com.intellij.pom.core.impl.PomModelImpl
import com.intellij.pom.tree.TreeAspect
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.psi.codeStyle.*
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier
import com.intellij.psi.impl.source.codeStyle.CodeStyleManagerImpl
import com.intellij.psi.impl.source.codeStyle.IndentHelper
import com.intellij.psi.impl.source.codeStyle.IndentHelperImpl
import com.intellij.psi.impl.source.codeStyle.JavaCodeStyleManagerImpl
import com.intellij.psi.impl.source.codeStyle.PersistableCodeStyleSchemes
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.impl.source.tree.JavaTreeCopyHandler
import com.intellij.psi.impl.source.tree.TreeCopyHandler
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.ui.EDT
import io.github.xyzboom.ssreducer.kotlin.MockJavaCodeSettingsProvider
import io.github.xyzboom.ssreducer.kotlin.MockSchemeManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaCompilationResult
import org.jetbrains.kotlin.analysis.api.components.KaCompilerTarget
import org.jetbrains.kotlin.analysis.api.fir.utils.KaFirCacheCleaner
import org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionRegistry
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.permissions.KaAnalysisPermissionRegistry
import org.jetbrains.kotlin.analysis.api.platform.KotlinMessageBusProvider
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.KotlinProjectMessageBusProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinAlwaysAccessibleLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.platform.resolution.KaResolutionActivityTracker
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.analysis.api.standalone.KotlinStaticPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.base.KotlinStandalonePlatformSettings
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneFirDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.standalone.base.modification.KotlinStandaloneModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.permissions.KotlinStandaloneAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.FirStandaloneServiceRegistrar
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve.LLFirResolutionActivityTracker
import org.jetbrains.kotlin.analysis.low.level.api.fir.providers.LLSealedInheritorsProvider
import org.jetbrains.kotlin.analysis.project.structure.builder.*
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.setupIdeaStandaloneExecution
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.fir.declarations.SealedClassInheritorsProvider
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.Path
import com.intellij.openapi.application.impl.PlatformCoroutineSupport
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.impl.DirectoryIndex
import com.intellij.openapi.roots.impl.DirectoryIndexImpl
import com.intellij.openapi.roots.impl.ProjectFileIndexImpl
import com.intellij.psi.impl.JavaPsiImplementationHelper
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndex
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndexContributor
import com.intellij.workspaceModel.core.fileIndex.impl.WorkspaceFileIndexImpl
import io.github.xyzboom.ssreducer.kotlin.MockJavaPsiImplementationHelper
import kotlin.jvm.java

@Suppress("UnstableApiUsage")
class KaSessionRunner(
    private val jvmTarget: JvmTarget = JvmTarget.DEFAULT,
    private val languageVersion: LanguageVersion = LanguageVersion.FIRST_NON_DEPRECATED,
    private val apiVersion: LanguageVersion = languageVersion,
    private val jdkHome: File? = null,
    private val moduleName: String = "<mock-module-name>",
    private val sourceRoots: List<File> = emptyList(),
    private val libraries: List<File> = emptyList(),
    private val friends: List<File> = emptyList(),
) {
    init {
        // We depend on swing (indirectly through PSI or something), so we want to declare headless mode,
        // to avoid accidentally starting the UI thread. But, don't set it if it was set externally.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true")
        }
        setupIdeaStandaloneExecution()
    }

    @OptIn(KaExperimentalApi::class, KaImplementationDetail::class, KaIdeApi::class)
    private fun createAASession(
        projectDisposable: Disposable,
    ): Triple<StandaloneAnalysisAPISession, KotlinCoreProjectEnvironment, List<KaModule>> {
        val kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment =
            StandaloneProjectFactory.createProjectEnvironment(
                projectDisposable,
                KotlinCoreApplicationEnvironmentMode.Production
            )

        val project: MockProject = kotlinCoreProjectEnvironment.project

        @Suppress("UnstableApiUsage")
        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea,
            KaResolveExtensionProvider.EP_NAME.name,
            KaResolveExtensionProvider::class.java
        )

        // replaces buildKtModuleProviderByCompilerConfiguration(compilerConfiguration)

        val projectStructureProvider = KtModuleProviderBuilder(
            kotlinCoreProjectEnvironment.environment, project
        ).apply {
            // todo support other platforms
            val platform = JvmPlatforms.jvmPlatformByTargetVersion(jvmTarget)

            fun KtModuleBuilder.addModuleDependencies(moduleName: String) {
                addRegularDependency(
                    buildKtLibraryModule {
                        this.platform = platform
                        addBinaryRoots(this@KaSessionRunner.libraries.map { it.toPath() })
                        libraryName = "Libraries for $moduleName"
                    }
                )

                addFriendDependency(
                    buildKtLibraryModule {
                        this.platform = platform
                        addBinaryRoots(this@KaSessionRunner.friends.map { it.toPath() })
                        libraryName = "Friends of $moduleName"
                    }
                )

                if (jdkHome != null) {
                    addRegularDependency(
                        buildKtLibraryModule {
                            this.platform = platform
                            addBinaryRootsFromJdkHome(jdkHome.toPath(), isJre = false)
                            libraryName = "JDK for $moduleName"
                        }
                    )
                }
            }

            buildKtSourceModule {
                languageVersionSettings = LanguageVersionSettingsImpl(
                    languageVersion,
                    ApiVersion.createByLanguageVersion(apiVersion)
                )

                this.platform = platform
                this.moduleName = this@KaSessionRunner.moduleName

                addModuleDependencies(moduleName)

                // Single file java source roots are added in reinitJavaFileManager() later.
                val roots = mutableListOf<File>()
                roots.addAll(this@KaSessionRunner.sourceRoots)
                addSourceRoots(roots.map { it.toPath() })
            }.apply(::addModule)

            this.platform = platform
        }.build()

        // register services and build session
        val ktModuleProviderImpl = projectStructureProvider
        val modules = ktModuleProviderImpl.allModules
        val allSourceFiles = ktModuleProviderImpl.allSourceFiles
        StandaloneProjectFactory.registerServicesForProjectEnvironment(
            kotlinCoreProjectEnvironment,
            projectStructureProvider,
        )
        val ktFiles = allSourceFiles.filterIsInstance<KtFile>()
        val libraryRoots = StandaloneProjectFactory.getAllBinaryRoots(modules, kotlinCoreProjectEnvironment.environment)
        val createPackagePartProvider =
            StandaloneProjectFactory.createPackagePartsProvider(
                libraryRoots,
            )

        kotlinCoreProjectEnvironment.registerApplicationServices(
            KaAnalysisPermissionRegistry::class.java,
            KaBaseAnalysisPermissionRegistry::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            KotlinAnalysisPermissionOptions::class.java,
            KotlinStandaloneAnalysisPermissionOptions::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            KaResolutionActivityTracker::class.java,
            LLFirResolutionActivityTracker::class.java
        )

        registerProjectServices(
            kotlinCoreProjectEnvironment,
            ktFiles,
            createPackagePartProvider,
        )

        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea, PsiTreeChangeListener.EP.name, PsiTreeChangeAdapter::class.java
        )
        registerExtraForEditPSI(kotlinCoreProjectEnvironment, project)
        return Triple(
            StandaloneAnalysisAPISession(kotlinCoreProjectEnvironment) {
                // This is only used by kapt4, which should query a provider, instead of have it passed here IMHO.
                // kapt4's implementation is static, which may or may not work for us depending on future use cases.
                // Let's implement it later if necessary.
                TODO("Not implemented yet.")
            },
            kotlinCoreProjectEnvironment,
            modules
        )
    }

    private fun registerExtraForEditPSI(
        kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
        project: MockProject
    ) {
        // this area is different from project.extensionArea
        val extensionArea = kotlinCoreProjectEnvironment.environment.application.extensionArea
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, TreeCopyHandler.EP_NAME, JavaTreeCopyHandler::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            IndentHelper::class.java,
            IndentHelperImpl::class.java
        )
        project.registerService(
            ProjectCodeStyleSettingsManager::class.java,
            ProjectCodeStyleSettingsManager::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            CodeStyleSettingsService::class.java,
            CodeStyleSettingsServiceImpl::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, CodeStyleSettingsProvider.EXTENSION_POINT_NAME, CodeStyleSettingsProvider::class.java
        )
        val javaCodeStyleProvider = MockJavaCodeSettingsProvider()
        extensionArea.getExtensionPoint(CodeStyleSettingsProvider.EXTENSION_POINT_NAME)
            .registerExtension(javaCodeStyleProvider, kotlinCoreProjectEnvironment.parentDisposable)
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, LanguageCodeStyleSettingsProvider.EP_NAME, LanguageCodeStyleSettingsProvider::class.java
        )
        extensionArea.getExtensionPoint(LanguageCodeStyleSettingsProvider.EP_NAME)
            .registerExtension(javaCodeStyleProvider, kotlinCoreProjectEnvironment.parentDisposable)
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea,
            LanguageCodeStyleSettingsContributor.EP_NAME,
            LanguageCodeStyleSettingsContributor::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, FileIndentOptionsProvider.EP_NAME, DetectableIndentOptionsProvider::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, FileTypeIndentOptionsProvider.EP_NAME, FileTypeIndentOptionsProvider::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            CodeStyleSchemes::class.java,
            PersistableCodeStyleSchemes::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            SchemeManagerFactory::class.java,
            MockSchemeManagerFactory::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            AppCodeStyleSettingsManager::class.java,
            AppCodeStyleSettingsManager::class.java
        )
        project.registerService(
            PomModel::class.java,
            PomModelImpl::class.java
        )
        project.registerService(
            TreeAspect::class.java,
            TreeAspect::class.java
        )
        project.registerService(
            CodeStyleManager::class.java,
            CodeStyleManagerImpl::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, FormattingService.EP_NAME, FormattingService::class.java
        )
        extensionArea.getExtensionPoint(FormattingService.EP_NAME)
            .registerExtension(CoreFormattingService(), kotlinCoreProjectEnvironment.parentDisposable)
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, LanguageFormattingRestriction.EP_NAME, LanguageFormattingRestriction::class.java
        )
        extensionArea.getExtensionPoint(LanguageFormattingRestriction.EP_NAME).apply {
            registerExtension(ExcludedFileFormattingRestriction(), kotlinCoreProjectEnvironment.parentDisposable)
            val clazz = Class.forName("com.intellij.lang.InvalidPsiAutoFormatRestriction")
            val instance = clazz.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance() as LanguageFormattingRestriction
            registerExtension(instance, kotlinCoreProjectEnvironment.parentDisposable)
        }
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, ExternalFormatProcessor.EP_NAME, ExternalFormatProcessor::class.java
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            Formatter::class.java,
            FormatterImpl::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, PostFormatProcessor.EP_NAME, PostFormatProcessor::class.java
        )
        project.registerService(
            JavaCodeStyleManager::class.java,
            JavaCodeStyleManagerImpl::class.java
        )
        project.registerService(
            CodeStyleCachingService::class.java,
            CodeStyleCachingServiceImpl::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, FileCodeStyleProvider.EP_NAME, FileCodeStyleProvider::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, CodeStyleSettingsModifier.EP_NAME, CodeStyleSettingsModifier::class.java
        )
        @Suppress("UNCHECKED_CAST")
        val styleClass =
            Class.forName("com.intellij.application.options.codeStyle.cache.CodeStyleCachedValueProviderService")
                    as Class<Any>
        val constructor = styleClass.getDeclaredConstructor(CoroutineScope::class.java).apply {
            isAccessible = true
        }
        project.registerService(
            styleClass,
            constructor.newInstance(CoroutineScope(Dispatchers.Unconfined)),
            kotlinCoreProjectEnvironment.parentDisposable
        )
        kotlinCoreProjectEnvironment.registerApplicationServices(
            CoroutineSupport::class.java,
            PlatformCoroutineSupport::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, ImportFilter.EP_NAME, ImportFilter::class.java
        )
        project.registerService(
            JavaPsiImplementationHelper::class.java,
            MockJavaPsiImplementationHelper::class.java
        )
        project.registerService(
            ProjectFileIndex::class.java,
            ProjectFileIndexImpl::class.java
        )
        project.registerService(
            WorkspaceFileIndex::class.java,
            WorkspaceFileIndexImpl::class.java
        )
        CoreApplicationEnvironment.registerExtensionPoint(
            extensionArea, WorkspaceFileIndexImpl.EP_NAME, WorkspaceFileIndexContributor::class.java
        )
        project.registerService(
            DirectoryIndex::class.java,
            DirectoryIndexImpl::class.java
        )
    }

    private fun <T> KotlinCoreProjectEnvironment.registerApplicationServices(
        serviceInterface: Class<T>,
        serviceImplementation: Class<out T>
    ) {
        val application = environment.application
        if (application.getServiceIfCreated(serviceInterface) == null) {
            KotlinCoreEnvironment.underApplicationLock {
                if (application.getServiceIfCreated(serviceInterface) == null) {
                    application.registerService(serviceInterface, serviceImplementation)
                }
            }
        }
    }

    // TODO: org.jetbrains.kotlin.analysis.providers.impl.KotlinStatic*
    private fun registerProjectServices(
        kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
        ktFiles: List<KtFile>,
        packagePartProvider: (GlobalSearchScope) -> PackagePartProvider,
    ) {
        val project = kotlinCoreProjectEnvironment.project
        project.apply {
            registerService(
                KotlinMessageBusProvider::class.java,
                KotlinProjectMessageBusProvider::class.java
            )
            FirStandaloneServiceRegistrar.registerProjectServices(project)
            FirStandaloneServiceRegistrar.registerProjectExtensionPoints(project)
            FirStandaloneServiceRegistrar.registerProjectModelServices(
                project,
                kotlinCoreProjectEnvironment.parentDisposable
            )

            registerService(
                KotlinModificationTrackerFactory::class.java,
                KotlinStandaloneModificationTrackerFactory::class.java
            )
            registerService(
                KotlinLifetimeTokenFactory::class.java,
                KotlinAlwaysAccessibleLifetimeTokenFactory::class.java
            )

            // Despite being a static implementation, this is only used by IDE tests
            registerService(
                KotlinAnnotationsResolverFactory::class.java,
                KotlinStandaloneAnnotationsResolverFactory(project, ktFiles)
            )
            registerService(
                KotlinDeclarationProviderFactory::class.java,
                KotlinStandaloneDeclarationProviderFactory(
                    this, kotlinCoreProjectEnvironment.environment, ktFiles
                )
            )
            registerService(
                KotlinDirectInheritorsProvider::class.java,
                KotlinStandaloneFirDirectInheritorsProvider::class.java
            )
            registerService(
                KotlinDeclarationProviderMerger::class.java,
                KotlinStandaloneDeclarationProviderMerger(this)
            )
            registerService(
                KotlinPackageProviderFactory::class.java,
                KotlinStandalonePackageProviderFactory(project, ktFiles)
            )

            registerService(
                SealedClassInheritorsProvider::class.java,
                LLSealedInheritorsProvider::class.java,
            )

            registerService(
                KotlinPackagePartProviderFactory::class.java,
                KotlinStaticPackagePartProviderFactory(packagePartProvider)
            )

            registerService(
                KotlinPlatformSettings::class.java,
                KotlinStandalonePlatformSettings()
            )
            // replace KaFirStopWorldCacheCleaner with no op implementation
            @OptIn(KaImplementationDetail::class)
            registerService(KaFirCacheCleaner::class.java, NoOpCacheCleaner::class.java)
        }
    }

    @RequiresReadLock
    @OptIn(KaExperimentalApi::class)
    fun performCompilation(file: KtFile): List<ByteArray> {
        val configuration = CompilerConfiguration() // configure compiler settings
        val target = KaCompilerTarget.Jvm(false, null, null)

        analyze(file) {
            val result = useSiteSession.compile(file, configuration, target) {
                true
            }

            when (result) {
                is KaCompilationResult.Success -> {
                    // Process compiled output
                    return result.output.map { it.content }
                }

                is KaCompilationResult.Failure -> {
                    // Handle compilation errors
                    throw RuntimeException("Compilation failed: ${result.errors}")
                }
            }
        }
    }

    @OptIn(KaImplementationDetail::class, KaExperimentalApi::class)
    fun runInSession(runnable: (StandaloneAnalysisAPISession, KotlinCoreProjectEnvironment, List<KaModule>) -> Unit) {
        val projectDisposable: Disposable = Disposer.newDisposable("StandaloneAnalysisAPISession.project")
        try {
            val (analysisAPISession, kotlinCoreProjectEnvironment, modules) =
                createAASession(projectDisposable)
            runnable(analysisAPISession, kotlinCoreProjectEnvironment, modules)
        } finally {
            maybeRunInWriteAction {
                Disposer.dispose(projectDisposable)
            }
        }
    }
}

private fun <R> maybeRunInWriteAction(f: () -> R) {
    synchronized(EDT::class.java) {
        if (!EDT.isCurrentThreadEdt())
            EDT.updateEdt()
        if (ApplicationManager.getApplication() != null) {
            runWriteAction {
                f()
            }
        } else {
            f()
        }
    }
}

/*
* NoOp implementation of the KaFirCacheCleaner
*
* The stop world cache cleaner that is registered by default [KaFirStopWorldCacheCleaner] can
* get analysis session into an invalid state which leads to build failures.
*/
@OptIn(KaImplementationDetail::class)
class NoOpCacheCleaner : KaFirCacheCleaner {
    override fun enterAnalysis() {}
    override fun exitAnalysis() {}
    override fun scheduleCleanup() {}
}

@OptIn(KaImplementationDetail::class)
private fun KtLibraryModuleBuilder.addBinaryRootsFromJdkHome(jdkHome: Path, isJre: Boolean) {
    val jdkRoots = LibraryUtils.findClassesFromJdkHome(jdkHome, isJre)
    addBinaryRoots(jdkRoots)
}