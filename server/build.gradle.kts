plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "3.1.2"
    id("com.gradleup.shadow") version "9.4.1"
}

group = "dev.knyazev"
version = "1.0.0"

application {
    mainClass.set("dev.knyazev.ApplicationKt")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.2"
val kotlinxSerializationVersion = "1.7.3"
val logbackVersion = "1.5.12"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-rate-limit-jvm:$ktorVersion")

    // Ktor client
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // .env file support for local dev
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}
