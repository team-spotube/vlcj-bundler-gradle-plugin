package org.spotube

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

interface VlcjBundlerExtension {
    /**
     * Target package for the generated loader file. Defaults to "org.spotube.core.audioplayer"
     */
    val packageName: Property<String>

    /**
     * Class/Object name for the generated file. Defaults to "VLCBundleLoader"
     */
    val objectName: Property<String>

    /**
     * Directory where the VLC native binaries are downloaded to. Reference this from your own
     * Compose Desktop configuration to bundle the natives, e.g. by setting it as the
     * `appResourcesRootDir`.
     */
    val vlcNativesDirectory: DirectoryProperty

    /**
     * Whether to download VLC and make it part of [vlcNativesDirectory]. Defaults to true.
     *
     * When false nothing is downloaded, the directory is left empty, and the generated loader
     * falls back to a system-wide VLC. Use it for packages that should rely on the distro's VLC
     * (e.g. deb/rpm) while still bundling for self-contained ones (e.g. AppImage, Windows, macOS).
     */
    val bundleVlc: Property<Boolean>

    /**
     * Linux only: the Ubuntu release the libvlc binaries are taken from. Its glibc is the minimum
     * the bundle runs on. Must be a release whose package names match "noble" (24.04). Defaults to "noble".
     */
    val linuxUbuntuRelease: Property<String>
}
