import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort

plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.15"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
    // SAST: SpotBugs + FindSecBugs (security-category, high-confidence gate). See `securityScan`.
    id("com.github.spotbugs") version "6.0.26"
}

group = "in.agreementmitra"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

// Dependency locking → a pinned, reproducible graph for the OSV dependency scan.
// Regenerate with: ./gradlew dependencies --write-locks
//
// Scope (policy): we lock — and therefore OSV scans — only the SHIPPING + TEST
// configurations. Build/dev/tool classpaths (spotbugs, spotbugsPlugins, jacoco*,
// developmentOnly, annotation processors) are excluded by design: they ship to no
// one and execute only on the build host, so they are out of the product risk
// surface. This is why we activate locking per-configuration instead of
// lockAllConfigurations(). See CLAUDE.md (Testing & Scanning) and
// config/osv-scanner.toml for the documented policy + the revisit-before-prod note.
// (Lock mode stays Gradle's default; we simply activate locking per in-scope config.)
listOf(
    "compileClasspath",
    "runtimeClasspath",
    "productionRuntimeClasspath",
    "testCompileClasspath",
    "testRuntimeClasspath",
).forEach { configName ->
    configurations.named(configName) { resolutionStrategy.activateDependencyLocking() }
}

extra["springModulithVersion"] = "1.4.12"

// commons-lang3 is pulled ONLY onto the test classpath (via commons-compress, a
// Testcontainers/MinIO transitive); it is on no shipping configuration. The Spring
// Boot BOM manages it to 3.17.0, which carries GHSA-j288-q9x7-2f5v (fixed in 3.18.0)
// and would trip the now-in-scope test-classpath OSV scan. commons-compress already
// requests 3.18.0, but io.spring.dependency-management forces the managed version
// onto transitives (overriding a plain Gradle constraint), so we override the BOM
// version property. Test-only in effect — does not touch the shipped graph.
extra["commons-lang3.version"] = "3.18.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Default-deny HTTP security baseline (CR-5): one SecurityFilterChain, fail-closed.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Actuator: only `health` exposed (see application.yml management.*).
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.postgresql:postgresql")

    // Flyway: schema is migration-managed (single source of truth); JPA stays ddl-auto: validate.
    // Flyway 10+ splits DB support into per-vendor modules — flyway-core alone lacks the Postgres
    // dialect. Both are managed by the Spring Boot BOM (no explicit version).
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Document rendering (headless Chromium). Uncomment when wiring `documents`:
    // implementation("com.microsoft.playwright:playwright:1.49.0")

    // Rules engine (future `rules` module):
    // implementation("org.drools:drools-ruleunits-engine:9.x")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")

    // Integration-test harness. @ServiceConnection lives in spring-boot-testcontainers;
    // Testcontainers module versions are managed by Spring Boot's BOM (no separate BOM).
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:minio")
    // MinIO Java client for the bucket round-trip (not managed by Boot's BOM).
    // Pinned to 8.6.0 — the fixed version for GHSA-h7rh-xfpj-hpcm (the advisory's
    // fix landed in 8.6.0, a minor bump; 8.5.x never carried it). Test scope only.
    testImplementation("io.minio:minio:8.6.0")
    // Force Bouncy Castle to the fixed 1.84 (minio 8.6.0 pulls vulnerable 1.81 —
    // GHSA-c3fc-8qff-9hwx). Test scope only; remediated by upgrade per policy.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.84")

    // WireMock — stubs the Leegality REST API for adapter + endpoint integration tests (no live
    // sandbox). The `-standalone` fat jar shades its Jetty/Jackson transitives, so it neither
    // collides with Boot's test classpath nor widens the OSV lockfile surface. Test scope only.
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")

    // SAST plugin: FindSecBugs rules for SpotBugs (security bug patterns).
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.withType<Test> { useJUnitPlatform() }

// --- Coverage gate (JaCoCo) -------------------------------------------------
// The gate exists from the first story but the floor is a deliberate 0.00 while
// the codebase is stub-only and integration tests skip without Docker.
// ratchet me up as real, covered behavior lands.
jacoco { toolVersion = "0.8.12" }

val jacocoExclusions = listOf(
    "**/AgreementMitraApplication.*",
    "**/package-info.*",
)

fun org.gradle.api.file.FileCollection.excludingGeneratedClasses(): org.gradle.api.file.FileCollection =
    files(map { fileTree(it) { exclude(jacocoExclusions) } })

tasks.test { finalizedBy(tasks.jacocoTestReport) }

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(classDirectories.excludingGeneratedClasses())
}

tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(classDirectories.excludingGeneratedClasses())
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.00".toBigDecimal() // ratchet me up
            }
        }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

// --- Security scanning (CR-4) ----------------------------------------------
// Two gates behind a single `securityScan` task, wired into `check`:
//   1. OSV-Scanner — dependency-vuln scan over the locked graph; fails on any
//      unsuppressed finding (suppressions live in config/osv-scanner.toml,
//      each with a reason + ignoreUntil expiry). Fail-closed if the binary is
//      absent. OSV-Scanner has no native CVSS threshold; fail-on-any + curated
//      ignores is its idiomatic, stricter model.
//   2. SpotBugs + FindSecBugs — SAST; an include filter scopes the gate to the
//      FindSecBugs SECURITY category at HIGH confidence so non-security bugs
//      don't fail the build. Suppressions in config/spotbugs-exclude.xml.

spotbugs {
    toolVersion = "4.8.6"
    effort = Effort.MAX
    reportLevel = Confidence.HIGH
    includeFilter = file("config/spotbugs-include.xml")
    excludeFilter = file("config/spotbugs-exclude.xml")
}

// Gate production code only; test code uses dummy data and is noisy for SAST.
tasks.named("spotbugsTest") { enabled = false }

tasks.spotbugsMain {
    reports.create("html") { required = true }
    reports.create("xml") { required = true }
}

val osvScan =
    tasks.register<Exec>("osvScan") {
        group = "verification"
        description = "Dependency-vulnerability scan (OSV-Scanner) over the locked graph."
        val lockfile = layout.projectDirectory.file("gradle.lockfile")
        val config = layout.projectDirectory.file("config/osv-scanner.toml")
        inputs.file(lockfile)
        inputs.file(config)
        // Fail-closed: a missing scanner must break the build, not silently pass.
        doFirst {
            val onPath =
                System.getenv("PATH").orEmpty().split(File.pathSeparator).any {
                    File(it, "osv-scanner").canExecute()
                }
            if (!onPath) {
                throw GradleException(
                    "osv-scanner not found on PATH. Install it (e.g. `brew install osv-scanner`) " +
                        "or see https://google.github.io/osv-scanner/installation/ — the dependency " +
                        "scan is a required gate and fails closed.",
                )
            }
        }
        // OSV-Scanner exits non-zero on any unsuppressed finding → that is the gate.
        commandLine(
            "osv-scanner",
            "scan",
            "--config=${config.asFile.path}",
            "--lockfile=${lockfile.asFile.path}",
        )
    }

tasks.register("securityScan") {
    group = "verification"
    description = "All security gates: OSV dependency scan + SpotBugs/FindSecBugs SAST."
    dependsOn(osvScan, tasks.named("spotbugsMain"))
}

tasks.check { dependsOn(tasks.named("securityScan")) }
