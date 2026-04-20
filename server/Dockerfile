# Kotlin/Native build — produces a ~7 MB static-ish Linux binary.
# Build stage runs on JVM (Gradle + konan toolchain), runtime stage ships only
# the binary + glibc-minimal base image. Matches ADR-021.
#
# Альтернатива — Dockerfile.jvm (собирает fat JAR поверх eclipse-temurin).
# Полезен для разработки и когда нужны полноценные SLF4J-логи.

FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
# Warm Gradle/konan caches. The link task is heavy (~1 min on cold cache) so
# caching build/ between image builds is worthwhile when iterating locally.
RUN gradle help --no-daemon || true
COPY src ./src
RUN gradle linkReleaseExecutableLinuxX64 --no-daemon

# Distroless base — no shell, no package manager, glibc only. Matches konan's
# linker output (dynamically linked against glibc 2.19+).
FROM gcr.io/distroless/base-debian12
WORKDIR /app
COPY --from=build /app/build/bin/linuxX64/releaseExecutable/knyazevs-server.kexe /app/knyazevs-server
# docs/ и репозиторий целиком монтируются как volumes в docker-compose.prod.yml —
# так индекс не вшит в образ и обновляется без пересборки.
EXPOSE 8080
ENTRYPOINT ["/app/knyazevs-server"]
