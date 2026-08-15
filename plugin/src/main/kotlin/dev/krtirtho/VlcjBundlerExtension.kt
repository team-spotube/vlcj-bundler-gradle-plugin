package dev.krtirtho

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

interface VlcjBundlerExtension {
    /**
     * Target package for the generated loader file. Defaults to "dev.krtirtho.spotube.core.audioplayer"
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
}