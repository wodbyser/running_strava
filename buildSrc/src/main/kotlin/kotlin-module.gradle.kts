import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

repositories {
    mavenCentral()
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "23"
    targetCompatibility = "23"
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

dependencies {
    annotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))
    api(platform(SpringBootPlugin.BOM_COORDINATES))
    compileOnly(platform(SpringBootPlugin.BOM_COORDINATES))
    runtimeOnly(platform(SpringBootPlugin.BOM_COORDINATES))
    testRuntimeOnly(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.slf4j:slf4j-api")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
