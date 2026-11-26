package com.running.frontend

import com.running.ai.AiProperties
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/** Makes the AI card work on every page that renders `ai.html`, without touching the five controllers. */
@ControllerAdvice
class AiModelAttributes(private val properties: AiProperties) {

    @ModelAttribute
    fun aiAttributes(model: Model) {
        model.addAttribute("aiEnabled", properties.isConfigured)
        model.addAttribute("aiModel", properties.model)
        model.addAttribute("aiProvider", properties.providerName)
    }
}
