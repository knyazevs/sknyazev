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
# distroless/cc — то же, что base (glibc, без shell/pkgmgr), но с runtime
# библиотеками C/C++: libgcc_s.so.1, libstdc++.so.6. K/N-бинарь линкуется
# против libgcc_s (атомики/эксепшны) транзитивно через нативные зависимости.
FROM gcr.io/distroless/cc-debian12
WORKDIR /app
COPY --from=build /app/build/bin/linuxX64/releaseExecutable/knyazevs-server.kexe /app/knyazevs-server
# libcrypt.so.1 в Debian 12 вынесена из glibc в отдельный пакет libcrypt1,
# которого нет даже в distroless/cc. Подкладываем из build-стадии.
COPY --from=build /lib/x86_64-linux-gnu/libcrypt.so.1 /lib/x86_64-linux-gnu/libcrypt.so.1
# docs/ и репозиторий целиком монтируются как volumes в docker-compose.prod.yml —
# так индекс не вшит в образ и обновляется без пересборки.
EXPOSE 8080
ENTRYPOINT ["/app/knyazevs-server"]
