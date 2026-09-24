// The keyspace and the commands on it. Common Kotlin, so the data structures and every command's
// semantics are tested on the JVM (fast) and on linuxX64 (the target that ships). Runs on the store
// thread only (research D-14): nothing here is thread-safe, on purpose.
plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
}

kotlin {
    jvm()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":resp"))
        }
    }
}
