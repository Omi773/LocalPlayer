plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        target.compilations.getByName("main").cinterops.create("flac") {
            defFile(project.file("src/nativeInterop/cinterop/flac.def"))
            includeDirs(rootProject.file("third_party/flac-ios/include"))
            extraOpts(
                "-staticLibrary", "libFLAC.a",
                "-libraryPath", rootProject.file("third_party/flac-ios/lib").absolutePath,
            )
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
