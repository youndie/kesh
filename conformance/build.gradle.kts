// The differential harness (research D-5): command scripts run against kesh and against Redis 7.2 over
// raw sockets, replies compared byte for byte. JVM only — it is a test tool, it talks to both servers
// over TCP, and nothing here ships (research D-2). `conformance/run.sh` starts both servers and runs it.
plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(libs.plugins.sborkaKmp)
    alias(libs.plugins.sborkaLint)
}

kotlin {
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass.set("io.github.youndie.kesh.conformance.MainKt")
            }
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":resp"))
            implementation(libs.lettuce)
        }
    }
}
