plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.0.2"
}

group = "in.agreementmitra"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

extra["springModulithVersion"] = "1.3.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.postgresql:postgresql")

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
    testImplementation("io.minio:minio:8.5.14")
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
