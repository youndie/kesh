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
        // The heap probe (B-19). Built as kesh's server is built — 256 KiB allocator pages (research
        // D-19) and the default collector — plus the GC log, which is a compiler flag rather than a
        // switch: the pauses it measures are read from that log. B-19's series before D-19 ran with
        // 16 KiB pages; the `page16` arm of `series.sh` rebuilds that.
        // The fork probe (B-14, research R-6): can a forked child of this runtime write a snapshot?
        binaries.executable("forkProbe") {
            entryPoint = "io.github.youndie.kesh.bench.fork.main"
            baseName = "kesh-fork-probe"
            binaryOption("fixedBlockPageSize", "256")
            freeCompilerArgs += "-Xruntime-logs=gc=info"
        }
        // The reference load (B-17): the dataset's own keys, §5a's mix, closed-loop pipelines.
        binaries.executable("load") {
            entryPoint = "io.github.youndie.kesh.bench.load.main"
            baseName = "kesh-load"
        }
        binaries.executable("heapProbe") {
            entryPoint = "io.github.youndie.kesh.bench.heap.main"
            baseName = "kesh-heap-probe"
            binaryOption("fixedBlockPageSize", "256")
            freeCompilerArgs += "-Xruntime-logs=gc=info,gcScheduler=info"
            // An arm: `-Pkesh.probeBinary=gc=pmcs` or `gcMarkSingleThreaded=true`. An unknown option is
            // a warning and the binary comes out identical, so `bench/heap-probe/series.sh` compares
            // each arm's md5 with the default before measuring it.
            providers.gradleProperty("kesh.probeBinary").orNull?.split(",")?.forEach {
                val (key, value) = it.split("=", limit = 2)
                binaryOption(key, value)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":resp"))
            // The dataset loaded into a store in-process: B-13's expiry test and B-14's fork probe.
            implementation(project(":store"))
            implementation(project(":snapshot"))
        }
        // The load generator talks to the server as a client: the server's own transport.
        nativeMain.dependencies {
            implementation(wip.kotlinx.coroutines.core)
            implementation("io.ktor:ktor-network:${wip.versions.ktor.get()}")
        }
    }
}
