plugins {
    id("kotlin-module")
    id("org.springframework.boot")
}

tasks.bootBuildImage {
    environment.set(mapOf(
        "BPE_DELIM_JAVA_TOOL_OPTIONS" to " ",
        "BPE_APPEND_JAVA_TOOL_OPTIONS" to "-XX:MaxDirectMemorySize=256M",
    ))
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

tasks.bootRun {
    val profiles = project.properties["spring.profiles.active"] ?: ""
    args = listOf("--spring.profiles.active=$profiles")
    jvmArgs = listOf("-server", "-ea", "-Xms256m", "-Xmx1024m")

    // Load .env file from project root and pass as environment variables
    val envFile = rootProject.projectDir.resolve(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    environment(parts[0].trim(), parts[1].trim())
                }
            }
    }
}
