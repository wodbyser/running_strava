plugins {
    id("kotlin-module")
    id("spring-boot-module")
}

dependencies {
    implementation(project(":core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // One provider-neutral client: OpenRouter, Groq, Gemini (OpenAI-compatible), Mistral and Ollama all speak this API.
    implementation("dev.langchain4j:langchain4j-open-ai:1.24.0")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 2.x on purpose: the 1.x docker client is rejected by current Docker engines (API version too old).
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

// LangChain4j imports Jackson 2.23.0; Spring Boot 3.4.4 is built and tested against 2.18.3. Keep the
// Boot-managed Jackson instead of silently upgrading it through a transitive dependency.
configurations.configureEach {
    resolutionStrategy {
        force(
            "com.fasterxml.jackson.core:jackson-annotations:2.18.3",
            "com.fasterxml.jackson.core:jackson-core:2.18.3",
            "com.fasterxml.jackson.core:jackson-databind:2.18.3",
            "com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.3",
            "com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3",
            "com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3",
            "com.fasterxml.jackson.module:jackson-module-parameter-names:2.18.3",
        )
    }
}
