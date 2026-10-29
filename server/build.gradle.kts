plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-jackson:3.0.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("org.mongodb:mongodb-driver-sync:5.2.1")
}

application {
    mainClass.set("mezzayn.AppKt")
}

kotlin {
    jvmToolchain(21)
}
