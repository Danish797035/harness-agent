package com.meenai.mina.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A released version: version number (without v), title, update description (Markdown), release date, release page, APK direct link. */
data class ReleaseInfo(
    val version: String,
    val name: String,
    /** Update instructions. */
    val notes: String,
    /** Release date yyyy-MM-dd; empty if the source is not written. */
    val date: String?,
    val pageUrl: String,
    val apkUrl: String?,
    /** The number of bytes in the APK attachment; it will be empty if the interface is not provided. */
    val apkSize: Long? = null,
    /** The summary of the APK attachment is in the shape of `sha256:<hex>`; the earlier attachment does not have it. At this time, it is only checked by the package name, version number and signature. */
    val apkDigest: String? = null,
    val tag: String = "",
    val runNumber: Long? = null,
) {
    /** The address opened by "Download from GitHub": If there is a direct link to the APK, download the APK. If not, open the release page. */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * Read the version information of the GitHub release page interface (releases/latest): tag_name, name,
 * body, published_at, html_url, and the browser_download_url of the local APK in assets (see [pickApk]).
 */
object Releases {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * In addition to the full-architecture `Mina-v<version>.apk`, there may also be packages with only native
     * libraries of a certain architecture, with names ending in `-<suffix>.apk`. The key is the Android ABI
     * name, and the value is the attachment name suffix.
     */
    private val ABI_APK_SUFFIX = mapOf("arm64-v8a" to "arm64")

    /** [deviceAbis] is the ABI supported by this machine, sorted by preference (Build.SUPPORTED_ABIS); when empty, only the full architecture package is taken. */
    fun fromGitHub(text: String, deviceAbis: List<String> = emptyList()): ReleaseInfo {
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o.str("tag_name") ?: error("no tag_name")
        val body = o.str("body").orEmpty()
        val apk = o["assets"]?.let { a ->
            runCatching { a.jsonArray }.getOrNull()?.map { it.jsonObject }?.let { pickApk(it, deviceAbis) }
        }
        val runNumber = AppVersion.parseRunNumber(tag)
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: version,
            notes = body.trim(),
            date = o.str("published_at")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = o.str("html_url") ?: "${HarnessLinks.RELEASES}/tag/$tag",
            apkUrl = apk?.str("browser_download_url"),
            apkSize = apk?.strOrNumber("size")?.toLongOrNull(),
            apkDigest = apk?.str("digest"),
            tag = tag,
            runNumber = runNumber,
        )
    }

    /**
     * If the machine supports a certain architecture and there is a dedicated package for this
     * architecture in the release, the dedicated package (smaller) will be used; otherwise, the full
     * architecture package will be used. Only the special packages for other architectures are not
     * available (cannot be installed), then open the release page.
     */
    private fun pickApk(assets: List<JsonObject>, deviceAbis: List<String>): JsonObject? {
        val apks = assets.filter { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
        fun named(suffix: String) = apks.firstOrNull { it.str("name")!!.endsWith("-$suffix.apk", ignoreCase = true) }
        val forDevice = deviceAbis.firstNotNullOfOrNull { abi -> ABI_APK_SUFFIX[abi]?.let(::named) }
        val universal = apks.firstOrNull { a -> ABI_APK_SUFFIX.values.none { a.str("name")!!.endsWith("-$it.apk", ignoreCase = true) } }
        return forDevice ?: universal
    }

    /** For saving: write down the latest version found so that the red dot will still be there when you open the app next time. */
    fun toJson(r: ReleaseInfo): JsonObject = buildJsonObject {
        put("version", r.version)
        put("name", r.name)
        put("notes", r.notes)
        r.date?.let { put("date", it) }
        put("pageUrl", r.pageUrl)
        r.apkUrl?.let { put("apkUrl", it) }
        r.apkSize?.let { put("apkSize", it) }
        r.apkDigest?.let { put("apkDigest", it) }
        put("tag", r.tag)
        r.runNumber?.let { put("runNumber", it) }
    }

    fun fromJson(o: JsonObject): ReleaseInfo? = runCatching {
        val tag = o.str("tag") ?: ("v" + o.str("version")!!)
        ReleaseInfo(
            version = o.str("version")!!,
            name = o.str("name") ?: o.str("version")!!,
            notes = o.str("notes").orEmpty(),
            date = o.str("date"),
            pageUrl = o.str("pageUrl") ?: HarnessLinks.RELEASES,
            apkUrl = o.str("apkUrl"),
            apkSize = o.strOrNumber("apkSize")?.toLongOrNull(),
            apkDigest = o.str("apkDigest"),
            tag = tag,
            runNumber = o.strOrNumber("runNumber")?.toLongOrNull() ?: AppVersion.parseRunNumber(tag),
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
