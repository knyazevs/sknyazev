FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
# Cache Gradle deps
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/knyazevs-server-1.0.0.jar server.jar
# docs/ is copied at build time so the server can index it on startup
COPY ../docs /app/docs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "server.jar"]
