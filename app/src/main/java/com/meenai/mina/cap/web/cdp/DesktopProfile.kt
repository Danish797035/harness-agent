package com.meenai.mina.cap.web.cdp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** A brand and version reported by the kernel (UA-CH's brands / fullVersionList each takes one version). */
data class Brand(val brand: String, val major: String, val full: String)

/**
 * The identity of the desktop version of the webpage: UA and UA-CH of the Linux desktop, the kernel
 * version number faithfully follows the native WebView, and the brand follows the kernel's own (removing
 * Android WebView). Everything that can be read on the page must match this identity: UA / UA-CH /
 * navigator.platform and the viewport, screen, and pixel ratio are set by simulation
 * ([userAgentOverride], [metricsOverride], [emulationCalls]); those that cannot be set by simulation
 * (touch capabilities, pointer and hover media queries, window frames, and WebView-specific traces) are
 * filled in by [identityScript] injected at the beginning of the document. The time zone does not change
 * and the local one is used; the language defaults to the local one. When the user selects the language
 * in Mina, the request header and navigator.language(s) are changed to that one (see [PageLanguage]).
 * The mobile version of the site will not be changed, and the real mobile phone identity of the machine
 * will be used.
 */
object DesktopProfile {
    /** Desktop viewport width in CSS pixels. */
    const val WIDTH = 1280
    const val FALLBACK_HEIGHT = 800
    /** Host display for desktop tabs: Draw the desktop viewport 1 CSS pixel by 1 pixel. */
    const val HOST_WIDTH = WIDTH
    const val HOST_HEIGHT = 800
    const val HOST_DPI = 160
    const val NAVIGATOR_PLATFORM = "Linux x86_64"
    const val PLATFORM = "Linux"
    /** The height of the parts outside the web page (tab bar, address bar) in the desktop browser window: the window frame is this much higher than the web page viewport. */
    const val WINDOW_CHROME_PX = 85
    private const val WEBVIEW_BRAND = "Android WebView"
    private val CHROME = Regex("""Chrome/(\d+)((?:\.\d+)*)""")

    /** Get the version from the kernel default UA: (major version, full version). */
    fun version(defaultUa: String): Pair<String, String>? =
        CHROME.find(defaultUa)?.let { m -> m.groupValues[1] to (m.groupValues[1] + m.groupValues[2]) }

    /** By convention, the version in UA only writes the main version (the rest are 0). */
    fun userAgent(major: String): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

    /** Remove Android WebView from the brand given by the kernel; use Chromium to add a placeholder brand if it is not available. */
    fun brands(engine: List<Brand>?, major: String, full: String): List<Brand> =
        engine?.filter { it.brand != WEBVIEW_BRAND }?.takeIf { l -> l.any { it.brand == "Chromium" } }
            ?: listOf(Brand("Chromium", major, full), Brand("Not_A Brand", "24", "24.0.0.0"))

    /**
     * Parameters of `Emulation.setUserAgentOverride`. There is no acceptLanguage here: the language
     * remains the user's when following the system; it is added by [PageLanguage.withLanguage] when the
     * user selects the language in Mina.
     */
    fun userAgentOverride(major: String, full: String, brands: List<Brand>): JsonObject = buildJsonObject {
        put("userAgent", userAgent(major))
        put("platform", NAVIGATOR_PLATFORM)
        put("userAgentMetadata", metadata(brands, full, PLATFORM, "", "x86", "", mobile = false, bitness = "64", wow64 = false))
    }

    /** CDP's `Emulation.UserAgentMetadata`(UA-CH); desktop identity is shared with the mobile version's default identity ([PageLanguage.mobileOverride]). */
    fun metadata(
        brands: List<Brand>, fullVersion: String, platform: String, platformVersion: String,
        architecture: String, model: String, mobile: Boolean, bitness: String, wow64: Boolean,
    ): JsonObject = buildJsonObject {
        put("brands", JsonArray(brands.map { b -> buildJsonObject { put("brand", b.brand); put("version", b.major) } }))
        put("fullVersionList", JsonArray(brands.map { b -> buildJsonObject { put("brand", b.brand); put("version", b.full) } }))
        put("fullVersion", fullVersion)
        put("platform", platform)
        put("platformVersion", platformVersion)
        put("architecture", architecture)
        put("model", model)
        put("mobile", mobile)
        put("bitness", bitness)
        put("wow64", wow64)
    }

    /** The viewport height is enlarged to the desktop width according to the aspect ratio of the host, and the entire page just fills the host; use 800 when the host size is unknown. */
    fun height(viewW: Int, viewH: Int): Int =
        if (viewW > 0 && viewH > 0) (WIDTH.toDouble() * viewH / viewW).roundToInt() else FALLBACK_HEIGHT

    /**
     * Scaling of the simulated screen: without time reduction, 1 CSS pixel occupies [density]
     * (devicePixelRatio of the page without simulation) view pixels, and the desktop-width page may only
     * reveal the left section; when zoomed to the full page, [WIDTH] just covers the view width, and the
     * ratio of view pixels to CSS pixels is viewW / WIDTH (press it for screen reading conversion). The
     * view width on the desktop host is [WIDTH], one to one.
     */
    fun fitScale(viewW: Int, density: Float): Double? =
        if (viewW > 0 && density > 0f) viewW / (WIDTH * density.toDouble()) else null

    /**
     * Parameters of `Emulation.setDeviceMetricsOverride`: desktop width, pixel ratio 1. Non-mobile
     * terminal; [scale] shrinks the entire page to the host width for display. The screen is calculated
     * as "the window covers the entire screen": the width is the same as the viewport, and the height is
     * the viewport plus the outer frame of the browser window ([WINDOW_CHROME_PX]), which is consistent
     * with the outerHeight given by the script.
     */
    fun metricsOverride(viewW: Int, viewH: Int, scale: Double?): JsonObject = buildJsonObject {
        val h = height(viewW, viewH)
        put("width", WIDTH)
        put("height", h)
        put("deviceScaleFactor", 1)
        put("mobile", false)
        put("screenWidth", WIDTH)
        put("screenHeight", h + WINDOW_CHROME_PX)
        put("screenOrientation", buildJsonObject {
            val landscape = WIDTH >= h + WINDOW_CHROME_PX
            put("type", if (landscape) "landscapePrimary" else "portraitPrimary")
            put("angle", 0)
        })
        scale?.let { put("scale", it) }
    }

    /**
     * The desktop version also sends simulation commands (methods, parameters) in addition to the UA and
     * viewport: touch simulation is not enabled, the mouse does not derive touch events, and the pointer
     * and mouse hover are reported (this does not work when the kernel does not recognize these media
     * features, which is covered by [identityScript]). Each entry is sent separately, and any rejection
     * will not affect the others.
     */
    fun emulationCalls(): List<Pair<String, JsonObject>> = listOf(
        "Emulation.setTouchEmulationEnabled" to buildJsonObject { put("enabled", false) },
        "Emulation.setEmitTouchEventsForMouse" to buildJsonObject { put("enabled", false) },
        "Emulation.setEmulatedMedia" to buildJsonObject {
            put("features", JsonArray(DESKTOP_MEDIA.map { (n, v) -> buildJsonObject { put("name", n); put("value", v) } }))
        },
    )

    /**
     * Which one should be used for the viewport of the desktop version: The Agent's label uses
     * simulation to indent the full page [WIDTH] view ([metricsOverride], screen reading and input
     * coordinates are converted according to [scale]); the label ([handOperated]) that the user sees and
     * clicks by hand does not add viewport simulation (cancel that one), and leaves it to WebView's own
     * wide viewport and overview mode—— When it is opened, the entire page is indented on the screen,
     * and you can zoom in with two fingers; the simulation locks the zoom, and you cannot zoom in. UA,
     * UA-CH, and identity patch labels are the same.
     */
    fun viewportCall(handOperated: Boolean, viewW: Int, viewH: Int, scale: Double?): Pair<String, JsonObject> =
        if (handOperated) "Emulation.clearDeviceMetricsOverride" to JsonObject(emptyMap())
        else "Emulation.setDeviceMetricsOverride" to metricsOverride(viewW, viewH, scale)

    /** When switching back to the mobile version, the media properties changed in [emulationCalls] will be revoked (the empty list will be revoked). */
    fun clearMediaCall(): Pair<String, JsonObject> =
        "Emulation.setEmulatedMedia" to buildJsonObject { put("features", JsonArray(emptyList())) }

    private val DESKTOP_MEDIA = listOf("pointer" to "fine", "any-pointer" to "fine", "hover" to "hover", "any-hover" to "hover")

    /**
     * The patches injected by the desktop version at the beginning of each document (including frames)
     * only make up for things that cannot be set by the simulation:
     * - - Touch: navigator.maxTouchPoints is 0, remove the ontouch* processor attribute, and
     * `document.createEvent('TouchEvent')` will be reported as unsupported like the desktop;
     * - - Pointer and hover: pointer / any-pointer / hover / any-hover in matchMedia. Press the mouse to
     * answer (the rest is left to the kernel as is), The media of the returned object is still the
     * original text given by the page (changed to the getter of the prototype, there are few attributes
     * on the object);
     * - - Window outer frame: outerWidth = innerWidth, outerHeight = innerHeight + [WINDOW_CHROME_PX];
     * - - navigator.platform is [NAVIGATOR_PLATFORM](matches UA even without DevTools); navigator.webdriver is
     * false; navigator.connection removes the type that is only available on mobile phones; if there is
     * no window.chrome, add an empty object (it is always there on desktop browsers). The changed getter
     * first adjusts the original one (it will still report Illegal invocation when the receiver is
     * wrong) and then gives the new value; the changed method retains the original length; their source
     * code is displayed as a built-in function, and toString in other windows looks the same (the patch
     * source code of each window is the same, so identify it according to the source code). The same
     * source framework (about:blank, srcdoc) created by the page script does not necessarily start to be
     * injected through the document: when it is first retrieved through contentWindow / contentDocument,
     * the same copy is added in the framework's own environment (when the framework prohibits eval, it
     * returns to be added in this environment). The windows that have reported 0 contact points will no
     * longer be filled. Do not touch UA, language, time zone, hardware parameters.
     */
    fun identityScript(): String = IDENTITY_SCRIPT
        .replace("__PLATFORM__", NAVIGATOR_PLATFORM)
        .replace("__CHROME_PX__", WINDOW_CHROME_PX.toString())

    private val IDENTITY_SCRIPT = """
(function desktopIdentity(W) {
  'use strict';
  try { if (!W.navigator || W.navigator.maxTouchPoints === 0) return; } catch (e) { return; }
  var FP = W.Function.prototype, readSource = FP.toString, ours = new Set(), lists = new WeakMap();
  function ownFn(fn) { ours.add(readSource.call(fn)); return fn; }
  function accessor(target, prop, answer) {
    var old = target && Object.getOwnPropertyDescriptor(target, prop);
    if (!old || typeof old.get !== 'function' || !old.configurable) return;
    var realGet = old.get;
    var get = ownFn(Object.getOwnPropertyDescriptor({ get [prop]() { return answer(realGet.call(this), this); } }, prop).get);
    Object.defineProperty(target, prop, { get: get, set: old.set, enumerable: old.enumerable, configurable: true });
  }
  function method(target, prop, body) {
    var old = target && Object.getOwnPropertyDescriptor(target, prop);
    if (!old || typeof old.value !== 'function' || !old.configurable) return;
    var real = old.value;
    var fn = ownFn({ [prop]() { return body.call(this, real, arguments); } }[prop]);
    Object.defineProperty(fn, 'length', { value: real.length, configurable: true });
    Object.defineProperty(target, prop, { value: fn, writable: old.writable, enumerable: old.enumerable, configurable: true });
  }
  function constant(v) { return function () { return v; }; }

  method(FP, 'toString', function (real, args) {
    var text = real.apply(this, args);
    return ours.has(text) ? 'function ' + this.name + '() { [native code] }' : text;
  });

  var nav = W.Navigator && W.Navigator.prototype;
  accessor(nav, 'maxTouchPoints', constant(0));
  accessor(nav, 'platform', constant('__PLATFORM__'));
  accessor(nav, 'webdriver', constant(false));

  [W, W.Document && W.Document.prototype, W.Element && W.Element.prototype,
   W.HTMLElement && W.HTMLElement.prototype, W.SVGElement && W.SVGElement.prototype].forEach(function (o) {
    if (!o) return;
    ['ontouchstart', 'ontouchend', 'ontouchmove', 'ontouchcancel'].forEach(function (p) {
      var d = Object.getOwnPropertyDescriptor(o, p);
      if (d && d.configurable) delete o[p];
    });
  });
  method(W.Document && W.Document.prototype, 'createEvent', function (real, args) {
    if (args.length && /^\s*touchevents?\s*${'$'}/i.test(String(args[0]))) {
      throw new W.DOMException("Failed to execute 'createEvent' on 'Document': The provided event type ('" + args[0] + "') is invalid.", 'NotSupportedError');
    }
    return real.apply(this, args);
  });

  var FEATURE = /\(\s*(any-)?(pointer|hover)\s*(?::\s*([a-z-]+)\s*)?\)/gi;
  var TRUE_Q = '(min-width: 0px)', FALSE_Q = '(max-width: 0px) and (min-width: 1px)';
  function asMouse(all, any, feature, value) {
    if (!value) return TRUE_Q;
    value = value.toLowerCase();
    return (feature.toLowerCase() === 'pointer' ? value === 'fine' : value === 'hover') ? TRUE_Q : FALSE_Q;
  }
  method(W, 'matchMedia', function (real, args) {
    if (!args.length) return real.apply(this, args);
    var query = String(args[0]);
    FEATURE.lastIndex = 0;
    if (!FEATURE.test(query)) return real.apply(this, args);
    var list = real.call(this, query.replace(FEATURE, asMouse));
    lists.set(list, real.call(this, query).media);
    return list;
  });
  accessor(W.MediaQueryList && W.MediaQueryList.prototype, 'media', function (v, self) { return lists.has(self) ? lists.get(self) : v; });

  accessor(W, 'outerWidth', function () { return W.innerWidth; });
  accessor(W, 'outerHeight', function () { return W.innerHeight + __CHROME_PX__; });

  var conn = W.NetworkInformation && W.NetworkInformation.prototype;
  ['type', 'ontypechange'].forEach(function (p) {
    var d = conn && Object.getOwnPropertyDescriptor(conn, p);
    if (d && d.configurable) delete conn[p];
  });

  if (!('chrome' in W)) Object.defineProperty(W, 'chrome', { value: {}, writable: true, enumerable: true, configurable: true });

  // The about:blank / srcdoc frame newly created in the script does not necessarily start to be injected
  // through the document: the same copy is added when it is retrieved for the first time.
  var self = desktopIdentity, source = readSource.call(self);
  function adopt(child) {
    try { if (!child || child === W || child.navigator.maxTouchPoints === 0) return; } catch (e) { return; }
    try { child.Function('W', 'return (' + source + ')(W);')(child); } catch (e) { try { self(child); } catch (e2) {} }
  }
  var frame = W.HTMLIFrameElement && W.HTMLIFrameElement.prototype;
  accessor(frame, 'contentWindow', function (v) { adopt(v); return v; });
  accessor(frame, 'contentDocument', function (v) { if (v) adopt(v.defaultView); return v; });
})(window);
"""
}
