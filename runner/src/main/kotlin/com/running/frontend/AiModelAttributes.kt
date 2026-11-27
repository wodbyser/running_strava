package com.running.frontend

import com.running.ai.AiModelCatalog
import com.running.ai.AiModelOption
import com.running.ai.AiProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/** Makes the AI card work on every page that renders `ai.html`, without touching the five controllers. */
@ControllerAdvice
class AiModelAttributes(
    private val properties: AiProperties,
    private val catalog: ObjectProvider<AiModelCatalog>,
) {

    @ModelAttribute
    fun aiAttributes(model: Model) {
        model.addAttribute("aiEnabled", properties.isConfigured)
        model.addAttribute("aiModel", properties.model)
        model.addAttribute("aiProvider", properties.providerName)
        if (properties.isConfigured) model.addAttribute("aiModels", models())
    }

    /**
     * The configured default first (always selectable, even when the catalogue cannot be read), then the free
     * models the provider lists. All of them are validated again server-side before any call.
     */
    private fun models(): List<AiModelOption> {
        val free = catalog.ifAvailable?.freeModels().orEmpty()
        val configured = free.firstOrNull { it.id == properties.model }
            ?: AiModelOption(properties.model, properties.model)
        return listOf(configured) + free.filter { it.id != properties.model }
    }
}
