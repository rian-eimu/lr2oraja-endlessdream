package org.endlessdream.extra

plugins {
    `java-library`
}

// use `-Dplatform=[platform]` or change this value to set the target platform for the jar
// Available platforms:
//    "windows", "linux", "macos"
val platform = System.getProperty("platform") ?: "windows"

// use `-Darch=[arch]` or change this value to set the target arch for the jar
// Available arch:
//    "x86-64", "aarch64"
// Defauls to x86-64
val arch = System.getProperty("arch") ?: "x86-64"

// テスト実行時はクロスビルド指定(-Dplatform/-Darch)ではなくホストOSのネイティブライブラリを解決する
val osName = System.getProperty("os.name").lowercase()
val hostPlatform = when {
    osName.contains("win") -> OperatingSystemFamily.WINDOWS
    osName.contains("mac") || osName.contains("darwin") -> OperatingSystemFamily.MACOS
    else -> OperatingSystemFamily.LINUX
}
val osArch = System.getProperty("os.arch").lowercase()
val hostArch = when {
    hostPlatform != OperatingSystemFamily.WINDOWS && (osArch == "aarch64" || osArch == "arm64") -> "aarch64"
    else -> "x86-64"
}

tasks {
    register<Copy>("resolveRuntimeClasspath") {
        from(configurations.runtimeClasspath)
        into(layout.buildDirectory.dir("runtimeClasspath"))
    }
}

configurations.matching {
    it.name.endsWith("runtimeClasspath", ignoreCase = true)
}.configureEach {
    val isTestClasspath = name.startsWith("test", ignoreCase = true)
    attributes {
        attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(if (isTestClasspath) hostPlatform else platform))
        attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named(if (isTestClasspath) hostArch else arch))
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

configurations.all {
    afterEvaluate {
        if (isCanBeResolved) {
            val isTargetRuntime = name.equals("runtimeClasspath", ignoreCase = true)
            attributes.attribute(platformModified, if (isTargetRuntime) "target" else "host")
        }
    }
}

val artifactType = Attribute.of("artifactType", String::class.java)
val platformModified = Attribute.of("platformModified", String::class.java)

dependencies {
    attributesSchema {
        attribute(platformModified)
    }
    artifactTypes.getByName("jar") {
        attributes.attribute(platformModified, "unfiltered")
    }
    registerTransform(PlatformFilter::class) {
        from.attribute(platformModified, "unfiltered").attribute(artifactType, "jar")
        to.attribute(platformModified, "target").attribute(artifactType, "jar")

        parameters {
            platformString = platform
            archString = arch
        }
    }
    registerTransform(PlatformFilter::class) {
        from.attribute(platformModified, "unfiltered").attribute(artifactType, "jar")
        to.attribute(platformModified, "host").attribute(artifactType, "jar")

        parameters {
            platformString = hostPlatform
            archString = hostArch
        }
    }
    components {
        // javacv does not make it easy to exclude these items in gradle, so a componentmetadata rule is as good
        // as it's going to get.
        withModule("org.bytedeco:javacv") {
            allVariants {
                withDependencies {
                    removeAll {
                        it.module.name in listOf(
                                "artoolkitplus",
                                "ffmpeg",
                                "flandmark",
                                "flycapture",
                                "ibfreenect2",
                                "leptonica",
                                "libdc1394",
                                "libfreenect",
                                "libfreenect2",
                                "librealsense",
                                "librealsense2",
                                "openblas",
                                "opencv",
                                "tesseract",
                                "videoinput",
                                "artoolkitplus-platform",
                                "ffmpeg-platform",
                                "flandmark-platform",
                                "flycapture-platform",
                                "ibfreenect2-platform",
                                "leptonica-platform",
                                "libdc1394-platform",
                                "libfreenect-platform",
                                "libfreenect2-platform",
                                "librealsense-platform",
                                "librealsense2-platform",
                                "openblas-platform",
                                "opencv-platform",
                                "tesseract-platform",
                                "videoinput-platform"
                        )
                    }
                }
            }
        }
        withModule<FFmpegRule>("org.bytedeco:javacpp")
        withModule<FFmpegRule>("org.bytedeco:ffmpeg")
    }
    components {

    }
}
