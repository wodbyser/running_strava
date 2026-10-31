package com.running.frontend

import com.running.analysis.LoadGlossary
import com.running.analysis.LoadOverview
import com.running.analysis.LoadPageBuilder
import com.running.analysis.LoadResponse
import com.running.analysis.LoadResponsePageBuilder
import com.running.analysis.LoadService
import com.running.analysis.LoadText
import com.running.analysis.LoadWindowSettings
import com.running.load.LoadWindows
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.util.UriComponentsBuilder

/** Read only pages. Everything is computed on demand from stored data; nothing is written. */
@Controller
class LoadController(private val service: LoadService) {

    data class LoadData(val overview: LoadOverview, val response: LoadResponse)

    @GetMapping("/load")
    fun overview(model: Model, @RequestParam params: Map<String, String>): String {
        val w = LoadWindowSettings.from(params)
        val o = service.overview(w)
        model.addAttribute("title", "Belasting")
        model.addAttribute("intro", LoadText.INTRO)
        model.addAttribute("safety", LoadText.SAFETY)
        model.addAttribute("p", LoadPageBuilder.build(o))
        model.addAttribute("g", LoadGlossary.terms)
        model.addAttribute("glossary", LoadGlossary.forPage("/load"))
        windowModel(model, w, "/load")
        model.addAttribute("otherPage", url("/load/response", w))
        model.addAttribute("version", o.algorithmVersion)
        return "load"
    }

    @GetMapping("/load/response")
    fun response(model: Model, @RequestParam params: Map<String, String>): String {
        val w = LoadWindowSettings.from(params)
        val r = service.response(w)
        model.addAttribute("title", "Reactie op training")
        model.addAttribute("intro", LoadText.INTRO_RESPONSE)
        model.addAttribute("safety", LoadText.SAFETY)
        model.addAttribute("p", LoadResponsePageBuilder.build(r))
        model.addAttribute("g", LoadGlossary.terms)
        model.addAttribute("glossary", LoadGlossary.forPage("/load/response"))
        windowModel(model, w, "/load/response")
        model.addAttribute("otherPage", url("/load", w))
        model.addAttribute("version", r.algorithmVersion)
        return "load-response"
    }

    @GetMapping("/load/data")
    @ResponseBody
    fun data(@RequestParam params: Map<String, String>): LoadData {
        val (o, r) = service.both(LoadWindowSettings.from(params))
        return LoadData(o, r)
    }

    private fun windowModel(model: Model, w: LoadWindows, path: String) {
        val changed = LoadWindowSettings.changed(w)
        model.addAttribute("spec", LoadWindowSettings.SPEC_BY_KEY)
        model.addAttribute("wv", LoadWindowSettings.values(w))
        model.addAttribute("keep", changed.mapValues { it.value.toString() })
        model.addAttribute("windowsChanged", changed.isNotEmpty())
        model.addAttribute("resetUrl", path)
        model.addAttribute("formAction", path)
    }

    private fun url(path: String, w: LoadWindows): String {
        val b = UriComponentsBuilder.fromPath(path)
        LoadWindowSettings.changed(w).forEach { (k, v) -> b.queryParam(k, v) }
        return b.encode().build().toUriString()
    }
}
