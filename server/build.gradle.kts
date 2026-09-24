// The binary. linuxX64 only: it is the target that ships (research D-2), `ktor-network`'s Native
// transport is what is being built on (research §1.4), and nothing here is meant to run on the JVM.
plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
    alias(libs.plugins.sborkaNativeService)
}

// Before the target is declared: the convention reads these when `linuxX64()` creates the executable.
nativeService {
    entryPoint = "io.github.youndie.kesh.server.main"
    baseName = "kesh"
}

kotlin {
    linuxX64()

    sourceSets {
        nativeMain.dependencies {
            implementation(project(":resp"))
            implementation(libs.kore.core)
            implementation(wip.kotlinx.coroutines.core)
            // The version is the shared catalog's `ktor` (3.6.0), read rather than repeated: the
            // catalog carries the version but no `ktor-network` entry, and a second number here is a
            // number that drifts from it (research D-6).
            implementation("io.ktor:ktor-network:${wip.versions.ktor.get()}")
        }
    }
}
