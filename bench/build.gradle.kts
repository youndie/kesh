// The reference dataset (research appendix A) as code: a seeded generator that writes RESP command
// streams for loading through the protocol, and hands the same entries to an in-process caller (the
// heap probe, B-19). Common Kotlin, so the determinism test runs on the JVM and on linuxX64 alike;
// the linuxX64 executable is the command-line tool.
plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
}

kotlin {
    jvm()
    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.kesh.bench.main"
            baseName = "kesh-dataset"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":resp"))
        }
    }
}
