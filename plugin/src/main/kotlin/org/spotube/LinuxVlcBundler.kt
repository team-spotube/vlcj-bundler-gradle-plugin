package org.spotube

import org.gradle.api.GradleException
import java.io.File
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Bundles a stripped, audio-only VLC for Linux. There is no official portable VLC build for Linux,
 * so the libvlc/plugin .deb files are taken from the Ubuntu archive, reduced to the same plugin
 * whitelist as Windows, and relocated with patchelf (RUNPATH=$ORIGIN) so they never touch the
 * host's VLC.
 *
 * Needs `ar`, `tar` (+ `zstd` for the .zst archives) and `patchelf` on the build machine.
 *
 * Libraries that must come from the host are NOT bundled, following the AppImage exclude list:
 * glibc, libstdc++, libgcc_s, libz, libgmp, ALSA, GL and X11. libdbus and libpulse are left to the
 * host too, since every desktop has them.
 */
internal object LinuxVlcBundler {
    private val corePackages = listOf("libvlc5", "libvlccore9", "vlc-plugin-base")

    // Shared libraries the whitelisted plugins link against that are not on every desktop.
    // Left to the host, per the AppImage exclude list: glibc, libstdc++, libgcc_s, libz, libgmp, libasound, GL/X11.
    // libdbus and libpulse are left to the host as well (every desktop has them).
    private val libraryPackages = listOf(
        "libidn12", "libmad0", "libsamplerate0", "libspeexdsp1", "libfaad2", "libflac12t64",
        "libmpg123-0t64", "libopus0", "libspeex1", "libvorbis0a", "libvorbisenc2", "libogg0",
        "libmpcdec6", "libgnutls30t64", "libnettle8t64", "libhogweed6t64",
        "libp11-kit0", "libtasn1-6", "libunistring5", "libidn2-0", "libffi8",
    )

    private val plugins = listOf(
        "access/filesystem", "access/http", "access/https",
        "audio_filter/audio_format", "audio_filter/mad", "audio_filter/samplerate",
        "audio_filter/scaletempo", "audio_filter/simple_channel_mixer",
        "audio_filter/speex_resampler", "audio_filter/trivial_channel_mixer",
        "audio_mixer/float_mixer", "audio_mixer/integer_mixer",
        "audio_output/adummy", "audio_output/alsa", "audio_output/pulse",
        "codec/adpcm", "codec/aes3", "codec/araw", "codec/faad", "codec/flac", "codec/g711",
        "codec/lpcm", "codec/mpg123", "codec/opus", "codec/speex", "codec/vorbis",
        "demux/aiff", "demux/au", "demux/caf", "demux/flacsys", "demux/mkv", "demux/mp4",
        "demux/mpc", "demux/ogg", "demux/playlist", "demux/rawaud", "demux/voc", "demux/wav",
        "misc/gnutls",
        "packetizer/packetizer_copy", "packetizer/packetizer_flac", "packetizer/packetizer_mlp",
        "packetizer/packetizer_mpeg4audio", "packetizer/packetizer_mpegaudio",
        "stream_filter/cache_block", "stream_filter/cache_read", "stream_filter/inflate",
        "stream_filter/prefetch",
    )


    fun bundle(target: File, isArm: Boolean, ubuntuRelease: String, workDir: File) {
        val (debArch, triplet, mirror) = if (isArm) {
            Triple("arm64", "aarch64-linux-gnu", "http://ports.ubuntu.com/ubuntu-ports")
        } else {
            Triple("amd64", "x86_64-linux-gnu", "http://archive.ubuntu.com/ubuntu")
        }

        for (tool in listOf("ar", "tar", "patchelf")) {
            val found = try {
                ProcessBuilder(tool, "--version").redirectErrorStream(true).start()
                    .also { it.inputStream.readBytes() }.waitFor() == 0
            } catch (_: Exception) {
                false
            }
            if (!found) throw GradleException("`$tool` is required to bundle VLC on Linux but was not found on PATH.")
        }

        target.deleteRecursively()
        target.mkdirs()
        val work = workDir.apply { deleteRecursively(); mkdirs() }
        val root = File(work, "root").apply { mkdirs() }

        val index = fetchPackageIndex(mirror, debArch, ubuntuRelease)
        for (pkg in corePackages + libraryPackages) {
            val filename = index[pkg] ?: throw GradleException("Package $pkg not found in the $ubuntuRelease index")
            val deb = File(work, "$pkg.deb")
            println("Downloading $pkg")
            URL("$mirror/$filename").openStream().use { input -> deb.outputStream().use { input.copyTo(it) } }
            extractDeb(deb, root, File(work, "ar-$pkg"))
        }

        val libDir = File(root, "usr/lib/$triplet")
        // Every versioned library under its soname (the .so.N symlink), real file copied
        libDir.listFiles { f -> Regex(""".*\.so\.\d+""").matches(f.name) }.orEmpty().forEach {
            it.copyTo(File(target, it.name), overwrite = true)
        }
        File(libDir, "vlc/libvlc_pulse.so.0").copyTo(File(target, "libvlc_pulse.so.0"), overwrite = true)
        // JNA looks the library up as libvlc.so
        File(target, "libvlc.so.5").copyTo(File(target, "libvlc.so"), overwrite = true)

        for (plugin in plugins) {
            val category = plugin.substringBefore('/')
            val name = plugin.substringAfter('/')
            val src = File(libDir, "vlc/plugins/$category/lib${name}_plugin.so")
            if (!src.exists()) throw GradleException("Missing VLC plugin: $plugin")
            src.copyTo(File(target, "plugins/$category/${src.name}"), overwrite = true)
        }

        // Bundled libraries find each other next to themselves, plugins two levels up
        target.listFiles { f -> f.isFile && ".so" in f.name }.orEmpty().forEach { patchRpath(it, "\$ORIGIN") }
        File(target, "plugins").walkTopDown().filter { it.isFile }.forEach { patchRpath(it, "\$ORIGIN/../..") }

        File(target, ".complete").writeText("")
        work.deleteRecursively()
        println("Linux VLC audio binaries bundled into $target")
    }

    /** name -> pool filename; `-updates` is read first so security/bugfix builds win. */
    private fun fetchPackageIndex(mirror: String, debArch: String, release: String): Map<String, String> {
        val index = HashMap<String, String>()
        for (suite in listOf("$release-updates", release)) {
            for (component in listOf("main", "universe")) {
                val url = URL("$mirror/dists/$suite/$component/binary-$debArch/Packages.gz")
                GZIPInputStream(url.openStream()).bufferedReader().useLines { lines ->
                    var name: String? = null
                    for (line in lines) {
                        when {
                            line.startsWith("Package: ") -> name = line.removePrefix("Package: ")
                            line.startsWith("Filename: ") -> name?.let { index.putIfAbsent(it, line.removePrefix("Filename: ")) }
                            line.isEmpty() -> name = null
                        }
                    }
                }
            }
        }
        return index
    }

    private fun extractDeb(deb: File, into: File, scratch: File) {
        scratch.mkdirs()
        exec(scratch, "ar", "x", deb.absolutePath)
        val data = scratch.listFiles { f -> f.name.startsWith("data.tar") }?.singleOrNull()
            ?: throw GradleException("No data archive in ${deb.name}")
        // GNU tar picks the decompressor from the content (zstd needs the zstd binary installed)
        exec(scratch, "tar", "-xf", data.absolutePath, "-C", into.absolutePath)
    }

    private fun patchRpath(file: File, rpath: String) {
        exec(file.parentFile, "patchelf", "--set-rpath", rpath, file.absolutePath)
    }

    private fun exec(dir: File, vararg command: String) {
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            throw GradleException("`${command.joinToString(" ")}` failed:\n$output")
        }
    }
}
