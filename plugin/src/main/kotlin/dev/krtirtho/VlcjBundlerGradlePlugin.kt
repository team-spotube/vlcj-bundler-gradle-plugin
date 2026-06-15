package dev.krtirtho

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
        val extension =
            project.extensions.create("vlcjBundler", VlcjBundlerExtension::class.java).apply {
                packageName.convention("dev.krtirtho.spotube.core.audioplayer")
                objectName.convention("VLCBundleLoader")
            }

        // 2. Register the Code Generation Task
        val generateLoaderTask =
            project.tasks.register("generateVlcBundleLoader", GenerateVlcLoaderTask::class.java) {
                packageName.set(extension.packageName)
                objectName.set(extension.objectName)
                outputDirectory.set(project.layout.buildDirectory.dir("generated/vlcjBundler/vlcjOut"))
            }

        // 3. Register the Automated Binary Extraction Task
        val downloadTask =
            project.tasks.register("downloadVlcNatives", DownloadVlcNativesTask::class.java) {
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
            val desktopExt =
                (composeExt as? ExtensionAware)?.extensions?.findByType(DesktopExtension::class.java)

            if (desktopExt != null) {
                val appResourcesRootDir =
                    desktopExt.application.nativeDistributions.appResourcesRootDir
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
// TASK: Download & extract VLC natives for both Windows and macOS
// --------------------------------------------------------------------
abstract class DownloadVlcNativesTask : DefaultTask() {

    @get:org.gradle.api.tasks.Internal
    abstract val buildDirectory: DirectoryProperty

    init {
        group = "dependencies"
        description = "Downloads and extracts lightweight VLC audio binaries for the current OS."
    }

    @TaskAction
    fun run() {
        val vlcVersion = "3.0.23"
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()

        val (osDir, downloadUrl) = when {
            os.contains("win") -> {
                if (arch.contains("aarch64") || arch.contains("arm64")) {
                    "win-arm64" to "http://ftp.videolan.org/pub/videolan/vlc/$vlcVersion/winarm64/vlc-$vlcVersion-winarm64.zip"
                } else {
                    "win-x64" to "http://ftp.videolan.org/pub/videolan/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip"
                }
            }

            os.contains("mac") -> {
                "macos-universal" to "http://ftp.videolan.org/pub/videolan/vlc/$vlcVersion/macosx/vlc-$vlcVersion-universal.dmg"
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

        when {
            os.contains("win") -> downloadAndStripWindows(downloadUrl, targetDir)
            os.contains("mac") -> downloadAndStripMacos(downloadUrl, targetDir)
        }
    }

    private fun downloadAndStripWindows(urlStr: String, targetDir: File) {
        val allowedFiles = setOf(
            "libvlc.dll",
            "libvlccore.dll",

            // access
            "plugins/access/libfilesystem_plugin.dll",
            "plugins/access/libhttp_plugin.dll",
            "plugins/access/libhttps_plugin.dll",

            // audio_filter
            "plugins/audio_filter/libaudio_format_plugin.dll",
            "plugins/audio_filter/libmad_plugin.dll",
            "plugins/audio_filter/libsamplerate_plugin.dll",
            "plugins/audio_filter/libsimple_channel_mixer_plugin.dll",
            "plugins/audio_filter/libspeex_resampler_plugin.dll",
            "plugins/audio_filter/libtrivial_channel_mixer_plugin.dll",

            // audio_mixer
            "plugins/audio_mixer/libfloat_mixer_plugin.dll",
            "plugins/audio_mixer/libinteger_mixer_plugin.dll",

            // audio_output
            "plugins/audio_output/libdirectsound_plugin.dll",
            "plugins/audio_output/libmmdevice_plugin.dll",
            "plugins/audio_output/libwasapi_plugin.dll",
            "plugins/audio_output/libwaveout_plugin.dll",

            // codec
            "plugins/codec/libadpcm_plugin.dll",
            "plugins/codec/libaes3_plugin.dll",
            "plugins/codec/libaraw_plugin.dll",
            "plugins/codec/libfaad_plugin.dll",
            "plugins/codec/libflac_plugin.dll",
            "plugins/codec/libg711_plugin.dll",
            "plugins/codec/liblpcm_plugin.dll",
            "plugins/codec/libmpg123_plugin.dll",
            "plugins/codec/libopus_plugin.dll",
            "plugins/codec/libspeex_plugin.dll",
            "plugins/codec/libvorbis_plugin.dll",

            // demux
            "plugins/demux/libaiff_plugin.dll",
            "plugins/demux/libau_plugin.dll",
            "plugins/demux/libcaf_plugin.dll",
            "plugins/demux/libflacsys_plugin.dll",
            "plugins/demux/libmp4_plugin.dll",
            "plugins/demux/libmpc_plugin.dll",
            "plugins/demux/libogg_plugin.dll",
            "plugins/demux/libplaylist_plugin.dll",
            "plugins/demux/librawaud_plugin.dll",
            "plugins/demux/libvoc_plugin.dll",
            "plugins/demux/libwav_plugin.dll",

            // misc
            "plugins/misc/libgnutls_plugin.dll",
            "plugins/misc/libxml_plugin.dll",

            // packetizer
            "plugins/packetizer/libpacketizer_copy_plugin.dll",
            "plugins/packetizer/libpacketizer_flac_plugin.dll",
            "plugins/packetizer/libpacketizer_mlp_plugin.dll",
            "plugins/packetizer/libpacketizer_mpeg4audio_plugin.dll",
            "plugins/packetizer/libpacketizer_mpegaudio_plugin.dll",

            // stream_filter
            "plugins/stream_filter/libcache_block_plugin.dll",
            "plugins/stream_filter/libcache_read_plugin.dll",
            "plugins/stream_filter/libinflate_plugin.dll",
            "plugins/stream_filter/libprefetch_plugin.dll"
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
                    if (relativePath in allowedFiles) {
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

    private fun downloadAndStripMacos(urlStr: String, targetDir: File) {
        val allowedPlugins = setOf(
            "plugins.dat",

            // access / networking
            "libfilesystem_plugin.dylib",
            "libhttp_plugin.dylib",
            "libhttps_plugin.dylib",

            // audio_filter / mixing
            "libaudio_format_plugin.dylib",
            "libmad_plugin.dylib",
            "libsamplerate_plugin.dylib",
            "libsimple_channel_mixer_plugin.dylib",
            "libspeex_resampler_plugin.dylib",
            "libtrivial_channel_mixer_plugin.dylib",
            "libfloat_mixer_plugin.dylib",
            "libinteger_mixer_plugin.dylib",

            // native apple coreaudio pipeline modules
            "libauhal_plugin.dylib",
            "libamem_plugin.dylib",

            // codecs
            "libadpcm_plugin.dylib",
            "libaes3_plugin.dylib",
            "libaraw_plugin.dylib",
            "libfaad_plugin.dylib",
            "libflac_plugin.dylib",
            "libg711_plugin.dylib",
            "liblpcm_plugin.dylib",
            "libmpg123_plugin.dylib",
            "libopus_plugin.dylib",
            "libspeex_plugin.dylib",
            "libvorbis_plugin.dylib",

            // demuxers
            "libaiff_plugin.dylib",
            "libau_plugin.dylib",
            "libcaf_plugin.dylib",
            "libflacsys_plugin.dylib",
            "libmp4_plugin.dylib",
            "libmpc_plugin.dylib",
            "libogg_plugin.dylib",
            "libplaylist_plugin.dylib",
            "librawaud_plugin.dylib",
            "libvoc_plugin.dylib",
            "libwav_plugin.dylib",

            // security & core parsing utilities
            "libgnutls_plugin.dylib",
            "libxml_plugin.dylib",

            // packetizers
            "libpacketizer_copy_plugin.dylib",
            "libpacketizer_flac_plugin.dylib",
            "libpacketizer_mlp_plugin.dylib",
            "libpacketizer_mpeg4audio_plugin.dylib",
            "libpacketizer_mpegaudio_plugin.dylib",

            // stream optimization filters
            "libcache_block_plugin.dylib",
            "libcache_read_plugin.dylib",
            "libinflate_plugin.dylib",
            "libprefetch_plugin.dylib"
        )

        val dmgFile = File(buildDirectory.asFile.get(), "vlc-tmp.dmg")
        println("Downloading VLC DMG...")
        if (!dmgFile.exists()) {
            URL(urlStr).openStream().use { input ->
                dmgFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        println("Mounting DMG...")
        val mountProcess =
            ProcessBuilder("hdiutil", "attach", "-nobrowse", "-readonly", dmgFile.absolutePath)
                .redirectErrorStream(true)
                .start()
        val mountOutput = mountProcess.inputStream.bufferedReader().readText()
        mountProcess.waitFor()
        val mountPoint = mountOutput.lines().lastOrNull { it.contains("/Volumes/") }
            ?.trim()
            ?.split("\t")?.lastOrNull()
            ?: throw GradleException("Failed to mount DMG, mount point not found")
        println("Mounted at: $mountPoint")

        try {
            val appDir = File(mountPoint, "VLC.app")
            if (!appDir.exists()) throw GradleException("VLC.app not found in DMG")

            val libSrcDir = File(appDir, "Contents/MacOS/lib")
            val pluginsSrcDir = File(appDir, "Contents/MacOS/plugins")

            // 1. Copy the ENTIRE lib/ directory recursively
            val targetLibDir = File(targetDir, "lib")
            libSrcDir.copyRecursively(targetLibDir, overwrite = true)

            // 2. Copy only whitelisted plugins into targetDir/plugins/ (Moved up)
            val targetPluginsDir = File(targetDir, "plugins")
            targetPluginsDir.mkdirs()
            allowedPlugins.forEach { pluginName ->
                val src = File(pluginsSrcDir, pluginName)
                if (src.exists()) {
                    src.copyTo(File(targetPluginsDir, pluginName), overwrite = true)
                } else {
                    println("Warning: missing whitelisted plugin: $pluginName")
                }
            }

            // 3. Structural Link Adjustments: libvlc
            ProcessBuilder(
                "install_name_tool",
                "-change", "@rpath/libvlccore.dylib", "@loader_path/libvlccore.dylib",
                File(targetLibDir, "libvlc.dylib").absolutePath
            ).inheritIO().start().waitFor()

            ProcessBuilder(
                "install_name_tool",
                "-id", "@loader_path/libvlccore.dylib",
                File(targetLibDir, "libvlccore.dylib").absolutePath
            ).inheritIO().start().waitFor()

            // 4. Structural Link Adjustments: Plugins (FIXES THE BUG)
            // Instructs plugins inside 'plugins/' to go up one directory and look in 'lib/'
            targetPluginsDir.walkTopDown().filter { it.isFile && it.extension == "dylib" }
                .forEach { pluginFile ->
                    ProcessBuilder(
                        "install_name_tool",
                        "-change",
                        "@rpath/libvlccore.dylib",
                        "@loader_path/../lib/libvlccore.dylib",
                        pluginFile.absolutePath
                    ).inheritIO().start().waitFor()
                }

            // 5. Clear quarantine flag
            ProcessBuilder("xattr", "-dr", "com.apple.quarantine", targetDir.absolutePath)
                .inheritIO().start().waitFor()

            // 6. CODESIGN: Must be run as the absolute last step after all modifications
            listOf(targetLibDir, targetPluginsDir).forEach { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension == "dylib" }.forEach { file ->
                    ProcessBuilder("codesign", "--force", "--sign", "-", file.absolutePath)
                        .inheritIO()
                        .start()
                        .waitFor()
                }
            }

        } finally {
            ProcessBuilder("hdiutil", "detach", mountPoint)
                .redirectErrorStream(true)
                .start()
                .waitFor()
//            dmgFile.delete()
        }
        println("macOS streaming audio binaries successfully filtered and unpacked.")
    }
}