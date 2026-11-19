package com.running

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.Locale

@SpringBootApplication
@EnableScheduling
open class Application

fun main(args: Array<String>) {
    // Numbers use a decimal point everywhere (UI, CSV, JSON export, AI prompts), whatever the OS locale is.
    // All app code also passes Locale.ROOT explicitly; this is a safety net for library formatting.
    Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT)
    val provisioning = args.any { it.startsWith("--provision-user") }
    if (provisioning && !multiUserRequested(args)) {
        System.err.println("--provision-user needs APP_MULTI_USER_ENABLED=true (or --app.multi-user.enabled=true)")
        kotlin.system.exitProcess(2)
    }
    runApplication<Application>(*args) {
        // The CLI creates a tenant and exits: no fixed port (a running instance keeps its own) and no warm-ups.
        if (provisioning) setDefaultProperties(mapOf("server.port" to "0", "garmin.load.warm-up" to "false", "garmin.race.warm-up" to "false"))
    }
}

private fun multiUserRequested(args: Array<String>): Boolean =
    args.contains("--app.multi-user.enabled=true") || System.getenv("APP_MULTI_USER_ENABLED") == "true"
