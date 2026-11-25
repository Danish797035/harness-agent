package com.meenai.mina.core.cap.web

import com.meenai.mina.core.script.JsSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The call of `mina.*` in the page script (web_script) is handed over to Mina's entrance. The port just
 * passes the call: each call is handed to [call] unchanged, and the return value (`{ok, value}` or
 * `{ok:false, error, message}`) is handed back to the script unchanged. The call to read the page
 * (query, queryAll, text) returns `{ok:true, read:{…}}`: the parameters have been verified and counted,
 * and the port reads them in the reading world.
 */
interface ScriptBridge {
    /** Throw [ScriptAbort] to end the entire script (exceeding the upper limit, requiring confirmation, stopped by the guard, and the page is handed over to the user). */
    suspend fun call(op: String, args: JsonObject): JsonObject
    /** `mina.log`: Waiting for reply. */
    fun log(text: String)
}

class ScriptAbort(val error: String, message: String) : Exception(message)

sealed interface ScriptRun {
    /** The run is finished; [valueJson] is the JSON of the return value (no return value is null). */
    data class Done(val valueJson: String?) : ScriptRun
    /** The entire page jumps away and the script stops here. */
    data object Navigated : ScriptRun
    /** [error]:script_error | timeout | devtools_unavailable | no_tab_open, or the error code of [ScriptAbort]. [line] is the line number in the script (starting from 1). */
    data class Failed(val error: String, val message: String, val line: Int? = null) : ScriptRun
}

/** A `mina.*` call in the page script. [id] is 0 indicating unequal reply (log). */
data class ScriptCall(val id: Int, val op: String, val args: JsonObject)

/**
 * How to use page scripts: The model code cannot get any page objects (by structure, not by disabling
 * the list).
 * - - Script world: a new isolated world. The pre-script only does two things - compile the model code
 * with the pre-saved AsyncFunction and upload the world's global object. (Including the prototype chain)
 * All names are covered as parameters and passed to undefined, and only [ALLOWED_GLOBALS] (pure
 * ECMAScript built-in, no DOM touch) is allowed; and then the paths that can run strings as code and
 * obtain global objects (Function constructor family, eval, call stack API) are blocked. The only model
 * code that can be used is `mina`. Each call to `mina` is a JSON message sent to Mina, and only JSON is
 * returned.
 * - - Read the world: another isolated world, only installed fixed [READER] (written by Mina herself),
 * read the DOM
 * according to the verified parameters, and return pure data (value coding such as passwords); The model
 * code is not in the same world as it and cannot get any objects in its hands.
 */
object PageScriptProtocol {
    const val SOURCE_URL = "mina-script.js"
    const val PRELUDE_URL = "mina-prelude.js"
    /** The upper limit of a single mina.wait (milliseconds). */
    const val MAX_WAIT_MS = 10_000
    const val WORLD_PREFIX = "mina-script-"
    const val READER_WORLD_PREFIX = "mina-reader-"
    /** What the script gets when it reads the value of an input box such as a password (the same as the coding of the running record). */
    const val SECRET_MASK = "***"

    /**
     * Input boxes that do not read values: the same rules as the page screen reading script (page.js) -
     * password boxes, hidden fields, and autocomplete marked passwords, bank cards, and one-time
     * verification codes.
     */
    val SECRET_AUTOCOMPLETE = listOf("current-password", "new-password", "cc-number", "cc-csc", "cc-exp", "cc-exp-month", "cc-exp-year", "one-time-code")
    val SECRET_INPUT_TYPES = listOf("password", "hidden")

    /** Global names available in model code: only ECMAScript's own values and functions, none of which lead to DOM or global objects. */
    val ALLOWED_GLOBALS = listOf(
        "undefined", "NaN", "Infinity", "Object", "Array", "Number", "Boolean", "String", "Symbol", "BigInt", "Date", "RegExp", "Promise",
        "Map", "Set", "WeakMap", "WeakSet", "WeakRef", "JSON", "Math", "Intl", "Reflect", "Proxy",
        "Error", "AggregateError", "EvalError", "RangeError", "ReferenceError", "SyntaxError", "TypeError", "URIError",
        "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array", "Uint32Array",
        "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array",
        "parseInt", "parseFloat", "isNaN", "isFinite", "encodeURI", "encodeURIComponent", "decodeURI", "decodeURIComponent", "Iterator",
    )

    /** It cannot be used as a parameter name (a reserved word in strict mode); eval is also blocked. */
    internal val RESERVED = listOf(
        "eval", "arguments", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum",
        "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super",
        "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static", "implements",
        "interface", "package", "private", "protected", "public", "await", "async", "mina",
    )

    /** Before the first line of the model code, there are several lines of compiled source code (`(async function anonymous(…`, `) {`, `'use strict';`, `return (async () => {`). */
    private const val LINE_OFFSET = 4

    private val json = Json { ignoreUnknownKeys = true }

    private fun js(v: List<String>): String = v.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    /**
     * Pre-script installed into the script world: save the originals to be used, block the way to run
     * the string as code, figure out the name to be masked, define `__MINARUN`. Each mina call is put
     * into the outbox with a number. Kotlin always hangs a [TAKE], and the result is returned as
     * `__minaSettle(id, r)`; `__minaEnd()` causes subsequent calls to fail, and the hanging [TAKE]
     * returns null. Binding without DevTools: Binding requires opening Runtime, and the debugger will be
     * visible on the page when it is opened.
     */
    val PRELUDE: String = """
(() => {
  'use strict';
  const G = globalThis, O = Object;
  if (O.prototype.hasOwnProperty.call(G, '__MINARUN')) return;
  const AsyncFunction = O.getPrototypeOf(async function () {}).constructor;
  const stringify = JSON.stringify, apply = Reflect.apply, timer = G.setTimeout, freeze = O.freeze;
  const fixed = (o, k, v) => { try { O.defineProperty(o, k, { value: v, writable: false, enumerable: false, configurable: false }); } catch (e) {} };
  const unavailable = function () { throw new TypeError('not available in web_script: the script has no access to the page; use mina.*'); };
  // Run the string as code (it will run in the global scope and get the global object), call the stack
  // API (get the function and this)
  for (const f of [function () {}, async function () {}, function* () {}, async function* () {}]) fixed(O.getPrototypeOf(f), 'constructor', unavailable);
  fixed(G, 'eval', unavailable);
  fixed(G, 'Function', unavailable);
  fixed(G.Error, 'prepareStackTrace', undefined);

  const pending = new Map();
  let seq = 0, ended = false;
  // Outbox: __minaTake hanging in Kotlin is waiting in takers, come and send one
  const outbox = [], takers = [];
  const post = (s) => { const t = takers.shift(); if (t) t(s); else outbox.push(s); };
  fixed(G, '__minaTake', () => new Promise((r) => {
    if (outbox.length) r(outbox.shift()); else if (ended) r(null); else takers.push(r);
  }));
  const call = (op, args) => new Promise((resolve, reject) => {
    if (ended) { reject(new Error('script_ended')); return; }
    const id = ++seq;
    pending.set(id, { resolve, reject });
    post(stringify({ id, op, args: args || {} }));
  });
  fixed(G, '__minaSettle', (id, r) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (r && r.ok) { p.resolve(r.value); return; }
    const e = new Error(((r && r.error) || 'failed') + (r && r.message ? ': ' + r.message : ''));
    e.code = r && r.error;
    p.reject(e);
  });
  fixed(G, '__minaEnd', () => {
    ended = true;
    for (const p of pending.values()) p.reject(new Error('script_ended'));
    pending.clear();
    for (const t of takers.splice(0)) t(null);
  });
  const sleep = (ms) => new Promise((r) => timer(r, ms));
  const refOf = (r) => (r && typeof r === 'object') ? r.ref : r;
  const str = (x) => { if (typeof x === 'string') return x; try { return stringify(x); } catch (e) { return String(x); } };
  const mina = freeze({
    snapshot: () => call('snapshot'),
    read: (opts) => call('read', opts || {}),
    query: (selector, opts) => call('query', { selector, ...(opts || {}) }),
    queryAll: (selector, opts) => call('queryAll', { selector, ...(opts || {}) }),
    text: (selector, opts) => call('text', { selector: selector || 'body', ...(opts || {}) }),
    tap: (ref) => call('tap', { ref: refOf(ref) }),
    type: (ref, text, opts) => call('type', { ref: refOf(ref), text, enter: !!(opts && opts.enter) }),
    scroll: (direction, opts) => call('scroll', { direction, ...(opts || {}) }),
    navigate: (url) => call('navigate', { url }),
    wait: async (w) => {
      if (typeof w === 'number') { await sleep(Math.max(0, Math.min(w, $MAX_WAIT_MS))); return true; }
      if (!w || typeof w.text !== 'string' || !w.text) throw new Error('invalid_arguments: mina.wait takes milliseconds or {text, timeoutMs}');
      return call('waitText', { text: w.text, timeoutMs: w.timeoutMs });
    },
    log: (...xs) => { if (!ended) post(stringify({ id: 0, op: 'log', args: { text: xs.map(str).join(' ') } })); },
  });

  // Every name on the global object of this world (including the prototype chain) is blocked, and only
  // pure ECMAScript built-in ones are allowed.
  const ALLOWED = new Set(${js(ALLOWED_GLOBALS)});
  const RESERVED = new Set(${js(RESERVED)});
  const names = new Set(['globalThis', 'window', 'self', 'document', 'location', 'top', 'parent', 'frames', 'opener']);
  for (let o = G; o; o = O.getPrototypeOf(o)) for (const k of O.getOwnPropertyNames(o)) {
    if (/^[A-Za-z_$][\w$]*$/.test(k) && !ALLOWED.has(k) && !RESERVED.has(k)) names.add(k);
  }
  names.add('__MINARUN');
  const shadow = [...names];
  const blanks = shadow.map(() => undefined);
  fixed(G, '__MINARUN', (code) => {
    // The model code is placed in the inner arrow function: the name it declares (even if it has the
    // same name as the masked one) is in its own scope and does not conflict with the parameters.
    const fn = new AsyncFunction(...shadow, 'mina', "'use strict';\nreturn (async () => {\n" + code + '\n})();\n//# sourceURL=$SOURCE_URL');
    return apply(fn, undefined, [...blanks, mina]).then((r) => (r === undefined ? null : stringify(r)));
  });
})();
//# sourceURL=$PRELUDE_URL
""".trimStart()

    /** Expressions that run model code (evaluated in the scripting world, etc. Promise). */
    fun run(code: String): String = "globalThis.__MINARUN(${JsonPrimitive(code)})"

    /**
     * There is no import allowed in the model code (check lexically, and block if it appears outside of
     * strings, comments, and regular expressions): Dynamic import runs the module in the global scope
     * and gets the global object. Returns the problem description, if there is no problem, it will be
     * null.
     */
    fun codeProblem(code: String): String? =
        if (JsSource.usesImport(code)) "import is not available in page scripts (write the word only inside strings or comments)" else null

    /**
     * Read the fixed reader in the world: press `{mode, selector, fields, limit, maxChars}` to read the
     * DOM, only pure data will be returned. mode: one (the first), all (list and total), count (number),
     * text (the combined visible text), has (whether there is the word [selector] in the page text).
     * Values such as passwords are coded with the value attribute, but the on* attribute is not given.
     */
    val READER: String = """
(() => {
  'use strict';
  if (Object.prototype.hasOwnProperty.call(globalThis, '__minaRead')) return;
  const MASK = '$SECRET_MASK';
  const SECRET_AC = new Set(${js(SECRET_AUTOCOMPLETE)});
  const SECRET_TYPES = new Set(${js(SECRET_INPUT_TYPES)});
  const secret = (el) => {
    if (el instanceof HTMLInputElement && SECRET_TYPES.has(String(el.type).toLowerCase())) return true;
    if (!(el instanceof HTMLInputElement) && !(el instanceof HTMLTextAreaElement)) return false;
    const ac = el.getAttribute('autocomplete');
    return !!ac && ac.toLowerCase().split(/\s+/).some((t) => SECRET_AC.has(t));
  };
  const clip = (s, n) => (s.length > n ? s.slice(0, n) : s);
  const textOf = (el) => (secret(el) ? '' : String(el.innerText ?? el.textContent ?? '').trim());
  const item = (el, f, n) => {
    const o = { tag: el.tagName.toLowerCase() };
    if (f.includes('text')) o.text = clip(textOf(el), n);
    if (f.includes('attrs')) {
      o.attrs = {};
      for (const a of el.attributes) {
        const k = a.name.toLowerCase();
        if (k.startsWith('on')) continue;
        o.attrs[k] = k === 'value' && secret(el) ? (a.value ? MASK : '') : clip(a.value, n);
      }
    }
    if (f.includes('value')) {
      o.value = 'value' in el ? (secret(el) ? (el.value ? MASK : '') : clip(String(el.value), n)) : null;
      if (el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio')) o.checked = el.checked;
      if (el instanceof HTMLSelectElement) o.selected = Array.from(el.selectedOptions, (x) => clip(x.text, n));
    }
    if (f.includes('box')) {
      const r = el.getBoundingClientRect(), w = innerWidth || 1, h = innerHeight || 1;
      o.box = { x: Math.round(r.x), y: Math.round(r.y), width: Math.round(r.width), height: Math.round(r.height),
        center: { x: Math.round(Math.min(999, Math.max(0, (r.x + r.width / 2) / w * 999))), y: Math.round(Math.min(999, Math.max(0, (r.y + r.height / 2) / h * 999)) ) },
        visible: r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < h && r.left < w };
    }
    return o;
  };
  Object.defineProperty(globalThis, '__minaRead', { value: (q) => {
    if (q.mode === 'has') return { ok: true, value: String(document.body ? document.body.innerText : '').includes(q.selector) };
    let list;
    try { list = document.querySelectorAll(q.selector); } catch (e) { return { ok: false, error: 'invalid_selector', message: String(e.message) }; }
    if (q.mode === 'count') return { ok: true, value: list.length };
    if (q.mode === 'text') {
      let t = '';
      for (const el of list) { const s = textOf(el); if (s) t += (t ? '\n' : '') + s; if (t.length >= q.maxChars) break; }
      return { ok: true, value: clip(t, q.maxChars) };
    }
    const items = [];
    for (let i = 0; i < list.length && items.length < q.limit; i++) items.push(item(list[i], q.fields, q.maxChars));
    return { ok: true, value: q.mode === 'one' ? (items[0] || null) : { items, total: list.length } };
  } });
})();
""".trimStart()

    /** In the reading world, the expression is read as [spec]. */
    fun read(spec: JsonObject): String = "globalThis.__minaRead($spec)"

    /**
     * Bottom line: When the script still issues a request, there will be a model script or pre-script in
     * the `Network.requestWillBeSent` initiation call stack. Look at the entire stack (including the
     * asynchronous parent chain).
     */
    fun requestFromScript(params: JsonObject): Boolean {
        var stack = (params["initiator"] as? JsonObject)?.get("stack") as? JsonObject
        var depth = 0
        while (stack != null && depth++ < 32) {
            val frames = stack["callFrames"] as? JsonArray
            if (frames != null && frames.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull in SCRIPT_URLS }) return true
            stack = stack["parent"] as? JsonObject
        }
        return false
    }

    /** Bottom line: When the script is running and there is no mina action, the main frame will jump - if it is not caused by mina action, it will be blocked. */
    fun navigationOutsideAction(params: JsonObject, mainFrameId: String, inAction: Boolean): Boolean =
        !inAction && (params["frameId"] as? JsonPrimitive)?.contentOrNull == mainFrameId

    private val SCRIPT_URLS = setOf(SOURCE_URL, PRELUDE_URL)

    /** Get the expression of the next message in the outbox (evaluate in the script world, wait for Promise; return null after closing). */
    const val TAKE = "globalThis.__minaTake()"

    /** A message in the outbox; not a valid call is null. */
    fun parseCall(payload: String): ScriptCall? {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val id = (o["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: return null
        val op = (o["op"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val args = o["args"] as? JsonObject ?: JsonObject(emptyMap())
        return ScriptCall(id, op, args)
    }

    /** Returns the result of the [id]th call. The result is a JSON object literal, directly embedded in the expression. */
    fun settle(id: Int, result: JsonObject): String = "globalThis.__minaSettle && globalThis.__minaSettle($id, $result)"

    const val END = "globalThis.__minaEnd && globalThis.__minaEnd()"

    private val STACK_LINE = Regex(Regex.escape(SOURCE_URL) + ":(\\d+):\\d+")

    /**
     * exceptionDetails of `Runtime.evaluate` → (description, line number in script). The line number is
     * taken from the position of the model script in the call stack, and converted to the line number
     * (starting from 1) written by the model; if there is a syntax error, there is no such stack, and
     * the line number is not reported.
     */
    fun error(details: JsonObject): Pair<String, Int?> {
        val exception = details["exception"] as? JsonObject
        val description = (exception?.get("description") as? JsonPrimitive)?.contentOrNull
            ?: (exception?.get("value") as? JsonPrimitive)?.contentOrNull
        val text = (details["text"] as? JsonPrimitive)?.contentOrNull
        val message = description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: text ?: "script failed"
        val line = description?.let { STACK_LINE.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.let { it - LINE_OFFSET }?.takeIf { it >= 1 }
        return message to line
    }
}
