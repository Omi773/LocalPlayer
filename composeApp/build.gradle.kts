plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val flacDir = project.file("libs/flac")

kotlin {
    iosArm64 {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        compilations.getByName("main").cinterops.create("flac") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/flac.def"))
            includeDirs(flacDir.resolve("include"))
            extraOpts("-libraryPath", flacDir.resolve("lib").absolutePath, "-staticLibrary", "libFLAC.a")
        }
    }

    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
            "-opt-in=kotlinx.cinterop.BetaInteropApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
