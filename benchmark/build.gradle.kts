import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxBenchmark)
    alias(libs.plugins.kotlinAllOpen)
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":reader"))
            implementation(libs.kotlinx.benchmark.runtime)
        }
        jvmMain {
            resources.srcDir("../reader/src/androidUnitTest/resources")
        }
    }
}

benchmark {
    targets {
        register("jvm")
    }
    configurations {
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 2
            iterationTimeUnit = "SECONDS"
            reportFormat = "json"
        }
    }
}
