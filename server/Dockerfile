# syntax=docker/dockerfile:1.7
#
# Kotlin/Native build — produces a ~7 MB static-ish Linux binary (glibc).
# Build stage: Gradle + konan toolchain на JVM.
# Runtime:     distroless/base (glibc, без shell/pkgmgr), совпадает с линкером konan.
# ADR-021.
#
# Альтернатива — Dockerfile.jvm (fat JAR поверх eclipse-temurin).
#
# Ключевые оптимизации:
#   1. Слои разнесены: файлы Gradle копируются ДО src — правка кода не
#      инвалидирует dependency-resolution-слой.
#   2. BuildKit cache mounts для ~/.gradle и ~/.konan — при сборке в Actions
#      с `cache-to/from: type=gha,mode=max` konan-тулчейн (~250 MB) и
#      артефакты Kotlin-plugin переживают между запусками.

FROM gradle:8-jdk21 AS build
WORKDIR /app

# 1) Файлы сборки — кэш слоя инвалидируется только при их изменении.
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ ./gradle/

# 2) Прогрев кэшей konan/Gradle. Скачивает konan-тулчейн и зависимости,
#    но НЕ компилирует src. Cache mounts переживут последующие сборки.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    gradle --no-daemon --console=plain \
      -Pkotlin.native.ignoreDisabledTargets=true \
      dependencies || true

# 3) Исходники — самый частый инвалидант.
COPY src ./src

# 4) Линковка релиза. Cache mounts дают тёплый старт linkRelease-задачи.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.konan \
    gradle --no-daemon --console=plain \
      linkReleaseExecutableLinuxX64

# ── Runtime ──────────────────────────────────────────────────────────────────
# Distroless base — glibc, без shell и пакетного менеджера. Совпадает с
# выводом линкера konan (динамически слинковано против glibc 2.19+).
FROM gcr.io/distroless/base-debian12
WORKDIR /app
COPY --from=build /app/build/bin/linuxX64/releaseExecutable/knyazevs-server.kexe /app/knyazevs-server
# docs/ и репозиторий целиком монтируются как volumes в docker-compose.prod.yml —
# так индекс не вшит в образ и обновляется без пересборки.
EXPOSE 8080
ENTRYPOINT ["/app/knyazevs-server"]
