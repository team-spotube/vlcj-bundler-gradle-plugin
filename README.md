# vlcj Bundler Gradle Plugin

A Gradle plugin for vlcj that downloads and bundles the necessary native libraries for vlcj into a single distributable package. This plugin simplifies the process of including vlcj in your projects by automating the packaging of native dependencies.

## Overview

This plugin automatically creates packaging tasks when applied alongside the [vlcj](https://www.capricasoftware.co.uk/projects/vlcj) plugin. It bundles vlcj native libraries into distributable packages.

## Requirements

- Gradle 9.4+
- Kotlin JVM plugin
- vlcj plugin

## Installation

### Using the Plugin DSL (Groovy)

```groovy
plugins {
    id 'dev.krtirtho.vlcj-bundler.gradle-plugin' version '0.1.0'
}
```

### Using the Plugin DSL (Kotlin)

```kotlin
plugins {
    id("dev.krtirtho.vlcj-bundler.gradle-plugin") version "0.1.0"
}
```

### Using Version Catalogs

Add the plugin to your version catalog (`gradle/libs.versions.toml`):

```toml
[plugins]
vlcj-bundler = { id = "dev.krtirtho.vlcj-bundler.gradle-plugin", version = "0.1.0" }
```

Then reference it in your build files:

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.vlcjBundler)
}
```

## Usage

Apply the plugin after the vlcj plugin:

```kotlin
plugins {
    id("dev.krtirtho.vlcj-bundler.gradle-plugin")
}
```

The plugin downloads the VLC native binaries to a build directory exposed via the
`vlcjBundler` extension. To actually bundle those binaries into your Compose Desktop
package, point Compose's `appResourcesRootDir` at that directory:

```kotlin
compose.desktop {
    application {
        nativeDistributions {
            appResourcesRootDir.set(vlcjBundler.vlcNativesDirectory)
        }
    }
}
```

### Configuration

The `vlcjBundler` extension exposes the following properties:

| Property | Description | Default |
| --- | --- | --- |
| `packageName` | Target package for the generated loader file | `dev.krtirtho.spotube.core.audioplayer` |
| `objectName` | Class/Object name for the generated file | `VLCBundleLoader` |
| `vlcNativesDirectory` | Directory the VLC natives are downloaded to | `build/vlc-natives` |

## License

Copyright (C) 2026 spotube.cc

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.

You may obtain a copy of the License at <http://www.apache.org/licenses/LICENSE-2.0>.

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
