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
    runApplication<Application>(*args)
}
