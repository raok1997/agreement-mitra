# AgreementMitra API image. Multi-stage: JDK 21 to build, JRE 21 to run.
#
# BUILD-GATE NOTE: this runs `bootJar`, deliberately NOT `check`. The `check`
# task chains `securityScan` (OSV-Scanner + SpotBugs/FindSecBugs), which is
# fail-closed on a missing `osv-scanner` binary and so cannot run inside a clean
# build image. The scanning gates stay a local/CI responsibility (CLAUDE.md,
# "Testing & Scanning"). A green image build is NOT a passed security scan.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# Build scripts before sources, so Gradle's dependency-resolution layer is cached
# independently of application-code churn. `config/` holds the SpotBugs include
# and exclude filters plus the OSV suppression baseline; the build script reads
# them at configuration time, so they must be present even for `bootJar`.
COPY gradlew gradle.properties gradle.lockfile settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY config ./config
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre AS runtime

# curl is here solely for the compose healthcheck: the base image ships none, and
# a port-open probe would report a JVM that started but failed to connect to
# Postgres as healthy. Actuator exposes only /actuator/health (application.yml),
# with show-details: never.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Run unprivileged. The JVM needs no root at any point, and this container is
# reachable from Caddy only.
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --home /app --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build --chown=app:app /build/build/libs/*.jar /app/app.jar
USER app

EXPOSE 8090

# MaxRAMPercentage lets the JVM size its heap from the container memory limit set
# in docker-compose.prod.yml rather than from the host's 12 GB. Asia/Kolkata is
# pinned explicitly so timestamps in the audit trail are unambiguous regardless
# of host TZ.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Kolkata"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
