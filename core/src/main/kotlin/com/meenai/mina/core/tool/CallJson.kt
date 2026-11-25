package com.meenai.mina.core.tool

import com.meenai.mina.core.net.UrlHost
import com.meenai.mina.core.observation.Place
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Calling parameters; if they are not JSON objects, they will be treated as empty parameters (rejected by the tool layer). */
internal fun parseArgs(json: String): JsonObject = try {
    Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
} catch (e: SerializationException) {
    JsonObject(emptyMap())
}

/** The "where" reported in the results: it is the front-end App on the mobile phone, and it is the site on the web page (the ones reported in the screen that comes with the action are also counted); both are reported as null. */
internal fun placeOf(content: JsonObject): Place? {
    val screen = content["screen"] as? JsonObject
    fun f(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
    (f(content, "currentApp") ?: f(screen, "currentApp"))?.let { return Place.app(it) }
    return (f(content, "currentSite") ?: f(screen, "currentSite"))?.let(Place::site)
}

/** The results include the site's actions (currentSite for reading the screen, URL for opening the web page and reading the page). */
private val SITE_RESULT_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage)

/**
 * Where is the task after this call? The result is not null (the previous one is used). "Where" is
 * divided into mobile apps and websites: changing to a web page or changing a site counts as a change of
 * place. The results of opening a web page and reading a page only include the URL: which site is
 * calculated based on the host of the URL (the same algorithm as the currentSite of the screen reading
 * report).
 */
internal fun placeAfter(verb: Verb?, content: JsonObject): Place? =
    placeOf(content) ?: if (verb in SITE_RESULT_VERBS && content["ok"] == JsonPrimitive(true))
        (content["url"] as? JsonPrimitive)?.contentOrNull?.let(UrlHost::of)?.let(Place::site) else null
