package com.running.ai

/** Provider-neutral port. One call, one prompt, one answer. Never logs the prompt or the answer. */
interface AiChat {
    fun complete(prompt: String): AiCompletion
}

data class AiCompletion(val text: String, val model: String)
