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

// --- Security overrides of Boot-BOM-managed versions ------------------------
// The Spring Boot 3.5.15 BOM manages these four to versions carrying open advisories.
// Bumping Boot does NOT help: 3.5.16 (the newest 3.5.x, checked 2026-09-05) manages the
// IDENTICAL versions — tomcat 10.1.55, postgresql 42.7.11, jackson-bom 2.21.4,
// log4j2 2.24.3 — and there is no 3.6/4.x line. So the only remediation lever is a
// per-property override, the same pattern as `commons-lang3.version` above.
//
// KEEP THESE ON THE NEXT BOOT UPGRADE: drop an entry only after verifying the new BOM
// manages that artifact at or above the version pinned here, or the fix silently regresses.
//
//   tomcat        10.1.55 → GHSA-9xv2-5v5q-p794 (9.8), GHSA-gcx9-497g-6cp6 (9.1),
//                           GHSA-h3x4-894j-xpx5 (9.1). OSV names 10.1.58 as the fix, but
//                           Apache never published 10.1.58 (Maven Central jumps 10.1.57 →
//                           10.1.59); 10.1.59 is the first available release carrying it.
//   postgresql    42.7.11 → GHSA-j92g-9f8w-j867 (8.2), fixed in 42.7.12.
//   jackson-bom    2.21.4 → GHSA-5gvw-p9qm-jgwh (6.5), GHSA-5jmj-h7xm-6q6v (5.3),
//                           GHSA-mhm7-754m-9p8w (6.5), fixed in 2.21.5. Overriding the BOM
//                           property moves the whole jackson family together (core,
//                           databind, dataformat-*, datatype-*, module-*), not just the
//                           flagged artifact — a split family is a runtime hazard OSV
//                           would not flag.
//   log4j2         2.24.3 → GHSA-qv9r-c865-cp47 (6.3), fixed in 2.25.5. Moves both
//                           log4j-api and log4j-to-slf4j (only these two are on the graph;
//                           log4j-core is not a dependency here).
extra["tomcat.version"] = "10.1.59"
extra["postgresql.version"] = "42.7.12"
extra["jackson-bom.version"] = "2.21.5"
extra["log4j2.version"] = "2.25.5"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Document rendering (CR-3a): Thymeleaf composes the rental-agreement template to
    // self-contained HTML (auto-escaping ON) for the Gotenberg render. Plain thymeleaf (not the
    // MVC-view starter) -- the engine is constructed directly, so no view auto-config is needed.
    // Version is Boot-BOM-managed. The HTTP client is the web starter's Spring RestClient. No
    // Playwright, no browser binary in the app -- Chromium runs in the Gotenberg compose service.
    implementation("org.thymeleaf:thymeleaf")
    // Template-definition model (CR: template-definition-model): definitions are authored in YAML,
    // compiled to canonical JSON, and structurally validated against a checked-in JSON Schema.
    // jackson-dataformat-yaml (Boot-BOM-managed) parses the YAML; json-schema-validator (offline,
    // Draft 2020-12-capable) does the structural validation. A shipping implementation dep, so it is
    // in the OSV/SpotBugs scan scope; pinned to a fixed release (not BOM-managed).
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Default-deny HTTP security baseline (CR-5): one SecurityFilterChain, fail-closed.
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Google OAuth login (google-oauth-login CR): brings the OAuth2 token-response client
    // primitives and the Nimbus JwtDecoder (spring-security-oauth2-jose) used to validate the
    // Google ID token against Google's JWKS. We drive the redirect ourselves and mint our own
    // opaque session -- Spring's oauth2Login auto-config stays dormant (no
    // spring.security.oauth2.client.registration.* props are set). A shipping dependency, so the
    // OSV/SpotBugs gate scopes it; version is Boot-BOM-managed.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // Actuator: only `health` exposed (see application.yml management.*).
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.postgresql:postgresql")

    // Flyway: schema is migration-managed (single source of truth); JPA stays ddl-auto: validate.
    // Flyway 10+ splits DB support into per-vendor modules — flyway-core alone lacks the Postgres
    // dialect. Both are managed by the Spring Boot BOM (no explicit version).
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PDF composition (CR-6): prepend the synthetic e-stamp page + overlay the per-page serial
    // onto the uploaded draft. PDFBox (pure-JVM) is correct here — page-prepend/ASCII overlay is
    // not Indic-script *shaping* (the Chromium-only rule is about rendering complex scripts). A
    // shipping dependency → in the OSV/SpotBugs scan scope; pinned to a fixed release.
    implementation("org.apache.pdfbox:pdfbox:3.0.5")

    // Object storage (MinIO local / S3-compatible prod) for signed-artifact blobs — the BlobStore
    // adapter is shipping code (CR-4). Pinned to 8.6.0 (fixed for GHSA-h7rh-xfpj-hpcm; the fix
    // landed in 8.6.0). Not managed by Boot's BOM. Now on the SHIPPING classpath, so it is in the
    // OSV scan's shipping scope — kept at the fixed version.
    implementation("io.minio:minio:8.6.0")
    // Force Bouncy Castle to the fixed 1.84 (minio 8.6.0 pulls vulnerable 1.81 —
    // GHSA-c3fc-8qff-9hwx). A direct shipping dependency so the override applies to the shipped
    // graph, not just tests (remediated by upgrade per policy).
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    // Outbound email (signed-delivery-and-closure CR): the ONE SMTP adapter behind the
    // vendor-neutral EmailSender seam - free Zoho Mail in development, Zoho ZeptoMail in
    // production (design D7); host/port/username/password are all configuration. The starter
    // brings jakarta.mail-api + Angus Mail and Spring's JavaMailSender/MimeMessageHelper, which
    // assembles the multipart MIME message (base64 attachment) we would otherwise hand-roll.
    // Boot's spring.mail.* auto-configuration stays dormant - the adapter builds its own
    // JavaMailSenderImpl from `mail.smtp.*` so the production host is never a default. A shipping
    // dependency, so it is in the OSV/SpotBugs scan scope; version is Boot-BOM-managed.
    implementation("org.springframework.boot:spring-boot-starter-mail")

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
    // io.minio + bouncycastle are now shipping `implementation` deps (above) — the BlobStore
    // adapter is production code — so they are already on the test classpath; no test-scope entry.

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

// --- Testcontainers reaper guard --------------------------------------------
// Ryuk is the ONLY cleanup that survives a killed test JVM. Testcontainers' own
// shutdown hook covers a clean exit; it does not run when the JVM is OOM-killed or
// the build is interrupted - which on a memory-tight dev box is the common case, not
// the rare one. With TESTCONTAINERS_RYUK_DISABLED=true set, every such run strands a
// Postgres + a MinIO container. They are invisible (random names, no compose project)
// and they accumulate: 32 of them once held ~1.8 GiB of a 3.7 GiB Docker VM here, with
// one spinning at 72% CPU, and the machine slowed to a crawl.
//
// So the build refuses to run rather than leak. This is a fail-closed gate, like the
// security scans: the disabled state is the dangerous one, so it must be loud.
//
// If Ryuk genuinely cannot start (Docker socket not where Testcontainers expects it),
// the fix is `./run-tests.sh`, which resolves the socket per Docker context and leaves
// Windows/Docker Desktop npipe alone - NOT disabling the reaper. To override anyway,
// for one command, in full knowledge that you are the cleanup:
//     ./gradlew test -Pallow.ryuk.disabled=true
val ryukDisabled = System.getenv("TESTCONTAINERS_RYUK_DISABLED")?.lowercase() == "true"
val ryukOverride = providers.gradleProperty("allow.ryuk.disabled").orNull?.lowercase() == "true"

tasks.withType<Test> {
    useJUnitPlatform()
    doFirst {
        if (ryukDisabled && !ryukOverride) {
            throw GradleException(
                """
                TESTCONTAINERS_RYUK_DISABLED=true - refusing to run.

                Ryuk is the reaper that removes test containers when the test JVM dies
                without running its shutdown hook (OOM-kill, Ctrl-C, crash). Disabled, every
                such run strands a Postgres + a MinIO container until Docker wedges.

                  Use instead:  ./run-tests.sh test        (resolves the Docker socket for you)
                  Override:     ./gradlew test -Pallow.ryuk.disabled=true
                  Sweep leaks:  ./scripts/sweep-test-containers.sh
                """.trimIndent()
            )
        }
    }
}

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
