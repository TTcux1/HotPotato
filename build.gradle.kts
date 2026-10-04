plugins {
    kotlin("jvm") version "2.0.21"
}

group = "dev.hotpotato"
version = "1.0.0"

val pluginAuthor = "TTcux"
val kotlinVersion = "2.0.21"
val mongoVersion = "5.1.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // Драйвер и kotlin-stdlib Paper скачивает сам по списку libraries в plugin.yml.
    compileOnly("org.mongodb:mongodb-driver-sync:$mongoVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf(
        "version" to project.version,
        "author" to pluginAuthor,
        "kotlinVersion" to kotlinVersion,
        "mongoVersion" to mongoVersion,
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
