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

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.mock.MockProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.ui.EDT
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaIdeApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaCompilationResult
import org.jetbrains.kotlin.analysis.api.components.KaCompilerTarget
import org.jetbrains.kotlin.analysis.api.fir.utils.KaFirCacheCleaner
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.platform.KotlinMessageBusProvider
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.KotlinProjectMessageBusProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.*
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
import org.jetbrains.kotlin.analysis.api.standalone.base.modification.KotlinStandaloneModificationTrackerFactory
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
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.fir.declarations.SealedClassInheritorsProvider
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneFirDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageProviderFactory
import org.jetbrains.kotlin.config.CompilerConfiguration

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
            org.jetbrains.kotlin.analysis.api.permissions.KaAnalysisPermissionRegistry::class.java,
            org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionRegistry::class.java
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