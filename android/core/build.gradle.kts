plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.pcontrol"
version = "1.0.0"

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

kotlin {
    jvmToolchain(17)
}
