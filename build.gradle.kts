import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

tasks.register("rootReleaseBenchmark") {
    group = "verification"
    description = "Runs :benchmark:jvmBenchmark and archives jvm.json to .validation/benchmark/."

    dependsOn(":benchmark:jvmBenchmark")

    // Capture paths at configuration time so the task body is config-cache compatible.
    val reportsDir = layout.projectDirectory.dir("benchmark/build/reports/benchmarks/main").asFile
    val archiveDir = layout.projectDirectory.dir(".validation/benchmark").asFile
    val projectRoot = layout.projectDirectory.asFile

    doLast {
        fun runGit(vararg args: String): String =
            ProcessBuilder("git", *args).directory(projectRoot).start()
                .inputStream.bufferedReader().use { it.readText().trim() }

        val sha = runGit("rev-parse", "--short", "HEAD")
        val dirty = runGit("status", "--porcelain").isNotBlank()
        val versionTag = if (dirty) "$sha-dirty" else sha

        val dateFolder = reportsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No benchmark report folder under $reportsDir")

        val jvmJson = File(dateFolder, "jvm.json")
        if (!jvmJson.exists()) throw GradleException("jvm.json not found in $dateFolder")

        archiveDir.mkdirs()
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val archived = File(archiveDir, "$versionTag-$timestamp.json")
        Files.move(jvmJson.toPath(), archived.toPath(), StandardCopyOption.REPLACE_EXISTING)
        println("Archived: ${archived.relativeTo(projectRoot)}")
    }
}

tasks.register("compareBenchmark") {
    group = "verification"
    description = "Runs :benchmark:jvmBenchmark and compares scores to the median of the last 10 archived runs."

    dependsOn(":benchmark:jvmBenchmark")

    val reportsDir = layout.projectDirectory.dir("benchmark/build/reports/benchmarks/main").asFile
    val archiveDir = layout.projectDirectory.dir(".validation/benchmark").asFile
    val summaryPath = providers.environmentVariable("GITHUB_STEP_SUMMARY").orNull

    doLast {
        @Suppress("UNCHECKED_CAST")
        fun readScoresByFixture(file: File): Map<String, Double> {
            // kotlinx-benchmark JSON: array of entries with params.fixture and primaryMetric.score.
            val root = groovy.json.JsonSlurper().parse(file) as List<Map<String, Any?>>
            return root.associate { entry ->
                val params = entry["params"] as Map<String, Any?>
                val fixture = params["fixture"] as String
                val score = (entry["primaryMetric"] as Map<String, Any?>)["score"] as Number
                fixture to score.toDouble()
            }
        }

        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        }

        val currentFolder = reportsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.lastModified() }
            ?: throw GradleException("No benchmark report folder under $reportsDir")
        val currentJson = File(currentFolder, "jvm.json")
        if (!currentJson.exists()) throw GradleException("jvm.json not found in $currentFolder")

        val current = readScoresByFixture(currentJson)

        // Last 10 archived runs, filename-sorted (timestamp suffix makes lexical == chronological).
        val archiveFiles = (archiveDir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" }
            .sortedBy { it.name }
            .takeLast(10)
        val history = mutableMapOf<String, MutableList<Double>>()
        archiveFiles.forEach { file ->
            readScoresByFixture(file).forEach { (fixture, score) ->
                history.getOrPut(fixture) { mutableListOf() }.add(score)
            }
        }

        val lines = buildList {
            add("## Benchmark comparison")
            add("")
            add("Baseline: median of last ${archiveFiles.size} archived run(s). Noise threshold: ±5%.")
            if (archiveFiles.isEmpty()) {
                add("")
                add("_No archived runs yet. Seed with `./gradlew rootReleaseBenchmark` on main._")
            }
            add("")
            add("| Fixture | Current (us) | Baseline median (us) | Delta | Status |")
            add("|---|---|---|---|---|")
            current.keys.sorted().forEach { fixture ->
                val curr = current.getValue(fixture)
                val past = history[fixture]
                if (past.isNullOrEmpty()) {
                    add("| $fixture | ${"%.3f".format(curr)} | — | — | no baseline |")
                } else {
                    val med = median(past)
                    val deltaPct = (curr - med) / med * 100.0
                    val status = when {
                        kotlin.math.abs(deltaPct) < 5.0 -> "within noise"
                        deltaPct < 0 -> "faster"
                        else -> "slower"
                    }
                    val sign = if (deltaPct >= 0) "+" else ""
                    add(
                        "| $fixture | ${"%.3f".format(curr)} | ${"%.3f".format(med)} " +
                            "| $sign${"%.1f".format(deltaPct)}% | $status |"
                    )
                }
            }
        }

        val markdown = lines.joinToString("\n")
        println(markdown)
        summaryPath?.let { File(it).appendText(markdown + "\n") }
    }
}
