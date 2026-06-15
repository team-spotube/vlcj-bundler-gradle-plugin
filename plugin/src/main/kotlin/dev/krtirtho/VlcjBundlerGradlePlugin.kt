package dev.krtirtho

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.TaskAction
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

class VlcjBundlerGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {

        // 1. Register User Extension DSL
        val extension = project.extensions.create("vlcjBundler", VlcjBundlerExtension::class.java).apply {
            packageName.convention("dev.krtirtho.spotube.core.audioplayer")
            objectName.convention("VLCBundleLoader")
        }

        // 2. Register the Code Generation Task
        val generateLoaderTask = project.tasks.register("generateVlcBundleLoader", GenerateVlcLoaderTask::class.java) {
            packageName.set(extension.packageName)
            objectName.set(extension.objectName)
            outputDirectory.set(project.layout.buildDirectory.dir("generated/vlcjBundler/vlcjOut"))
        }

        // 3. Register the Automated Binary Extraction Task
        val downloadTask = project.tasks.register("downloadVlcNatives", DownloadVlcNativesTask::class.java) {
            buildDirectory.set(project.layout.buildDirectory)
        }

        // 4. Source set registration – JVM only (desktop)
        project.afterEvaluate {
            val isMultiplatform = plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
            val isJvm = plugins.hasPlugin("org.jetbrains.kotlin.jvm")

            if (isMultiplatform) {
                val kmpExtension = extensions.getByType(KotlinMultiplatformExtension::class.java)
                kmpExtension.sourceSets.findByName("jvmMain")?.kotlin?.srcDir(generateLoaderTask)
            } else if (isJvm) {
                val kotlinExtension = extensions.getByType(KotlinProjectExtension::class.java)
                kotlinExtension.sourceSets.findByName("main")?.kotlin?.srcDir(generateLoaderTask)
            }
        }

        // 5. Reactive Hook into JetBrains Compose Plugin Setup
        project.plugins.withId("org.jetbrains.compose") {
            val composeExt = project.extensions.findByType(ComposeExtension::class.java)
            val desktopExt = (composeExt as? ExtensionAware)?.extensions?.findByType(DesktopExtension::class.java)

            if (desktopExt != null) {
                val appResourcesRootDir = desktopExt.application.nativeDistributions.appResourcesRootDir
                appResourcesRootDir.set(project.layout.buildDirectory.dir("vlc-natives"))
            }
        }

        // 6. Map Task Dependencies Automatically
        project.tasks.configureEach {
            if (name.startsWith("package") || name.startsWith("prepareAppResources") || name == "run") {
                dependsOn(downloadTask)
            }
        }
    }
}

// --------------------------------------------------------------------
// TASK: Download & extract VLC natives (with corrected backticks)
// --------------------------------------------------------------------
abstract class DownloadVlcNativesTask : DefaultTask() {

    @get:org.gradle.api.tasks.Internal
    abstract val buildDirectory: DirectoryProperty

    init {
        group = "dependencies"
        description = "Downloads and extracts lightweight VLC audio binaries directly into the build cache."
    }

    @TaskAction
    fun run() {
        val vlcVersion = "3.0.23"
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        val (osDir, downloadUrl) = when {
            os.contains("win") -> {
                if (arch.contains("aarch64") || arch.contains("arm64")) {
                    "win-arm64" to "https://get.videolan.org/vlc/$vlcVersion/winarm64/vlc-$vlcVersion-winarm64.zip"
                } else {
                    "win-x64" to "https://get.videolan.org/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip"
                }
            }
            os.contains("mac") -> {
                "macos-universal" to "https://get.videolan.org/vlc/$vlcVersion/macosx/vlc-$vlcVersion-universal.dmg"
            }
            else -> "linux-x64" to ""
        }

        if (downloadUrl.isEmpty()) {
            logger.warn("VLC native auto-download is not configured for this operating system.")
            return
        }

        val targetDir = buildDirectory.dir("vlc-natives/$osDir").get().asFile
        if (targetDir.exists() && targetDir.list()?.isNotEmpty() == true) {
            println("VLC binaries already present in build folder cache. Skipping download.")
            return
        }

        println("Downloading stripped VLC assets to build sandbox target: $targetDir")
        targetDir.mkdirs()

        if (os.contains("win")) {
            downloadAndStripWindows(downloadUrl, targetDir)
        } else if (os.contains("mac")) {
            println("⚠️ Automatic extraction of Mac DMG files needs terminal utility mounting pipelines.")
            println("Please manually copy/extract files inside: $targetDir")
        }
    }

    private fun downloadAndStripWindows(urlStr: String, targetDir: File) {
        val allowedPluginFolders = listOf(
            "access", "access_output", "audio_filter", "audio_mixer",
            "audio_output", "codec", "control", "demux",
            "keystore", "logger", "meta_engine", "misc", "packetizer",
            "services_discovery", "stream_filter"
        )

        URL(urlStr).openStream().use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val relativePath = name.substringAfter("/")
                    if (relativePath.isEmpty()) {
                        entry = zip.nextEntry
                        continue
                    }

                    val isCoreLib = relativePath == "libvlc.dll" || relativePath == "libvlccore.dll"
                    val isAudioPlugin = relativePath.startsWith("plugins/") && allowedPluginFolders.any { folder ->
                        relativePath.startsWith("plugins/$folder/")
                    }

                    // Fixed: removed illegal backticks around relativePath
                    val isVideoAsset = relativePath.contains("avcodec") ||
                            relativePath.contains("x264") ||
                            relativePath.contains("x265") ||
                            relativePath.contains("d3d")

                    if ((isCoreLib || isAudioPlugin) && !isVideoAsset) {
                        val destFile = File(targetDir, relativePath)
                        if (entry.isDirectory) {
                            destFile.mkdirs()
                        } else {
                            destFile.parentFile.mkdirs()
                            FileOutputStream(destFile).use { out -> zip.copyTo(out) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        println("Windows streaming audio binaries successfully filtered and unpacked.")
    }
}