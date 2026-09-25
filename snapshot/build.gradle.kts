// The snapshot format: the whole dataset as one file of kesh's own (brief §2 — not RDB), and its
// reading back. Common Kotlin over a byte sink and source, so the format is tested on the JVM and on
// linuxX64; the file itself — temporary name, fsync, rename — is the server's (B-14).
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
            implementation(project(":store"))
        }
        commonTest.dependencies {
            implementation(project(":resp"))
        }
    }
}
