// The RESP2 codec. Common Kotlin with no I/O and no dependencies, so the same tests run on the JVM
// (fast, for development) and on linuxX64 (the target that ships).
plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
}

kotlin {
    jvm()
    linuxX64()
}
