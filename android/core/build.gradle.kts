plugins {
    kotlin("jvm")
}

group = "com.pcontrol"
version = "1.0.0"

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
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
