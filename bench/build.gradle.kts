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
        // The heap probe (B-19). Built as kesh's server is built — `fixedBlockPageSize=16`, which
        // `sborka.native-service` gives the server, and the default collector — plus the GC log, which
        // is a compiler flag rather than a switch: the pauses it measures are read from that log.
        binaries.executable("heapProbe") {
            entryPoint = "io.github.youndie.kesh.bench.heap.main"
            baseName = "kesh-heap-probe"
            binaryOption("fixedBlockPageSize", "16")
            freeCompilerArgs += "-Xruntime-logs=gc=info,gcScheduler=info"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":resp"))
        }
    }
}
