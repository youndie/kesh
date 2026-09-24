rootProject.name = "kesh"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()

        // Written out by hand because `pluginManagement` is evaluated before sborka's settings plugin,
        // which is itself fetched through it. Filtered so that an unreachable portfolio repository
        // fails only the artefacts it serves, not every dependency. Goes away when sborka and kore
        // reach Maven Central.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("io\\.github\\.youndie.*") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"

    // Repositories, the `.editorconfig` check, and the shared `wip` catalog. The catalog carries the
    // compiler (Kotlin 2.4.20) and ktor (3.6.0): kesh reads both from there and pins neither itself
    // (research D-6, D-7). The catalog's version is this plugin's own.
    id("io.github.youndie.sborka.settings") version "0.4.0.91"
}

// The RESP2 codec: common Kotlin, no I/O.
include(":resp")

// The binary: listener, connections, the store thread, kore's shutdown plan.
include(":server")

// `store`, `snapshot`, `conformance`, `bench` and `deploy/` arrive with the items that give them
// code (B-05, B-14, B-04, B-03, B-16). A module with no code has no build to verify, and the shape of
// each one — `conformance` on the JVM, `bench` partly shell — is decided by the item that needs it.
