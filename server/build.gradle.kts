plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.knyazev"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }

    linuxX64 {
        binaries {
            executable {
                entryPoint = "dev.knyazev.main"
                baseName = "knyazevs-server"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.ktor.server.common)
            implementation(libs.bundles.ktor.client.common)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.atomicfu)
            implementation(libs.okio)
            implementation(libs.dotenv.kmp)
            implementation(libs.kotlin.logging)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.server.call.logging)
            // SLF4J binding — kotlin-logging delegates to SLF4J on JVM
            implementation(libs.logback.classic)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Fat JAR for JVM target (replaces shadow plugin — works cleanly with KMP)
tasks.register<Jar>("jvmFatJar") {
    group = "build"
    description = "Assembles a fat JAR for the JVM target (used by Dockerfile)."
    archiveBaseName.set("knyazevs-server")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.knyazev.BootstrapKt"
    }
    val jvmJar = tasks.named<Jar>("jvmJar")
    dependsOn(jvmJar)
    from(jvmJar.map { zipTree(it.archiveFile) })
    from({
        configurations.named("jvmRuntimeClasspath").get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}

// Alias so existing `gradle shadowJar` command still works in Dockerfile
tasks.register("shadowJar") {
    group = "build"
    description = "Alias for jvmFatJar — preserves existing Dockerfile build command."
    dependsOn("jvmFatJar")
}
