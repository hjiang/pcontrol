plugins {
    kotlin("jvm")
}

group = "com.pcontrol"
version = "1.0.0"

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
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
