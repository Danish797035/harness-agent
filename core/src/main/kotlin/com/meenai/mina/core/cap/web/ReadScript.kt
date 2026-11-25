package com.meenai.mina.core.cap.web

import com.meenai.mina.core.record.Redactor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

const val READ_SCRIPT_DEFAULT_TIMEOUT_MS = 10_000
const val READ_SCRIPT_MAX_TIMEOUT_MS = 30_000
/** The maximum length of time to wait for a sleep. */
const val READ_SLEEP_MAX_MS = 5_000

/**
 * How to read scripts (web_evaluate_js, site commands): Run in an isolated world in the main frame of
 * the current tag. You can see the real DOM, but you cannot touch the original.
 * - - The model code is compiled according to the web_script method: the names on the global object of
 * this world are all covered as parameters, leaving only the pure ECMAScript built-in and a few names
 * given below.
 * (document, window, location, fetch, state, args, sleep, getComputedStyle, URL, URLSearchParams).
 * - - The given page objects are all wrapped: reading attributes is read as usual (the read object is
 * wrapped in another layer), writing, deleting, defining attributes, and changing prototypes all report
 * read_only;
 * Only the registered read-only methods (search, read attributes, volume position, iteration) of the
 * function can be called, and other functions (point, focus, submit, change DOM, window opening, jump)
 * all report read_only. The native function only gets the original, and the model always gets the
 * package; the built-in objects that the model can encounter are frozen first, and the global names are
 * set to read-only. They cannot be changed to intercept the original or replace the functions used for
 * checking.
 * - - The value of input boxes such as passwords, hidden fields, verification codes, and bank cards is
 * read as `***`. Only [SECRET_FIELD_PROPS] on the interface of the input box itself can be read;
 * The same applies to same-origin iframe, shadow root, and template (use native getters to identify
 * nodes, not instanceof). The innerHTML class is coded on a copy that does not belong to any window,
 * without running the page code; when there is a secret textarea with words in the outer element, the
 * textContent class is also fetched from the copy. The sub-text node of the secret textarea and the
 * value attribute node of the secret input box are read as `***`, even when they are removed from the
 * page. It is not possible to select elements by the value attribute (the value can be guessed bit by
 * bit). The selector first restores according to CSS rules and then makes a judgment ([SelectorPolicy]).
 * The selector and attribute name parameters are first changed to strings and only changed once: the one
 * that is checked is the one handed to the browser.
 * - - Read the script without touching the network: blocked_network([NETWORK_MESSAGE]) is reported when
 * fetch is called; XMLHttpRequest, sendBeacon, WebSocket, EventSource,
 * The new Image() class also reports blocked_network when called and constructed through packaging; when
 * creating elements and changing src/href, the original page is changed (read_only). You can only read
 * what is already on the page.
 * - - state is an object that remains in the reading world of this tag: it is shared by several reading
 * scripts on the same page; it disappears when the page changes the document.
 * Bottom line (on the DevTools event): If the script makes any network request or the main frame jumps
 * when running, reading the script will end with an error.
 */
object ReadScriptProtocol {
    /** Read the script for instructions when you want to use the Internet. */
    const val NETWORK_MESSAGE = "network access is not allowed in read scripts; use web_capture to read what the page loaded"

    /** The name of the page function and constructor that can send requests: when called through packaging, the constructor reports blocked_network (not just read_only), and clearly explains why. */
    val NETWORK_NAMES = listOf(
        "fetch", "sendBeacon", "XMLHttpRequest", "WebSocket", "EventSource", "Image", "Audio", "Request",
        "Worker", "SharedWorker", "RTCPeerConnection", "importScripts",
    )

    const val WORLD_PREFIX = "mina-read-"
    const val SOURCE_URL = "mina-read.js"
    const val PRELUDE_URL = "mina-read-prelude.js"
    const val MASK = PageScriptProtocol.SECRET_MASK

    /** The names that can be used in the model code other than the pure ECMAScript built-in are also the order in which parameters are passed. */
    val PROVIDED = listOf("document", "window", "location", "fetch", "state", "args", "sleep", "getComputedStyle", "URL", "URLSearchParams")

    /**
     * A read-only method that can be called via the wrapper, per interface. Skip that cannot be found on
     * the prototype (WebView differs in different system versions). An interface with Symbol.iterator
     * additionally releases its iterator.
     */
    val READ_METHODS: Map<String, List<String>> = linkedMapOf(
        "Node" to listOf("contains", "hasChildNodes", "getRootNode", "compareDocumentPosition", "isEqualNode", "isSameNode", "lookupPrefix", "lookupNamespaceURI", "isDefaultNamespace"),
        "Element" to listOf(
            "querySelector", "querySelectorAll", "closest", "matches", "webkitMatchesSelector", "getAttribute", "getAttributeNS", "getAttributeNames",
            "hasAttribute", "hasAttributeNS", "hasAttributes", "getElementsByClassName", "getElementsByTagName", "getElementsByTagNameNS",
            "getBoundingClientRect", "getClientRects", "checkVisibility",
        ),
        "Document" to listOf(
            "querySelector", "querySelectorAll", "getElementById", "getElementsByClassName", "getElementsByTagName", "getElementsByTagNameNS",
            "getElementsByName", "elementFromPoint", "elementsFromPoint", "hasFocus",
        ),
        "DocumentFragment" to listOf("querySelector", "querySelectorAll", "getElementById"),
        "CharacterData" to listOf("substringData"),
        "NodeList" to listOf("item", "forEach", "entries", "keys", "values"),
        "HTMLCollection" to listOf("item", "namedItem"),
        "HTMLFormControlsCollection" to listOf("namedItem"),
        "HTMLOptionsCollection" to listOf("item", "namedItem"),
        "HTMLSelectElement" to listOf("item", "namedItem"),
        "HTMLAnchorElement" to listOf("toString"),
        "HTMLAreaElement" to listOf("toString"),
        "NamedNodeMap" to listOf("item", "getNamedItem", "getNamedItemNS"),
        "DOMTokenList" to listOf("item", "contains", "forEach", "entries", "keys", "values"),
        "CSSStyleDeclaration" to listOf("getPropertyValue", "getPropertyPriority", "item"),
        "DOMRectReadOnly" to listOf("toJSON"),
        "DOMRectList" to listOf("item"),
        "MediaQueryList" to listOf(),
    )

    /** Read-only methods on the global object itself. */
    val GLOBAL_READ_METHODS = listOf("getComputedStyle", "matchMedia")

    /** Unreadable attributes: Cookies, local storage (login credentials are often in them). */
    val DENIED_PROPS = listOf("cookie", "localStorage", "sessionStorage", "indexedDB", "caches", "cookieStore", "credentials", "serviceWorker", "clipboard", "openDatabase")

    /**
     * The attributes that can be read on the own interface (HTMLInputElement, HTMLTextAreaElement) of
     * the secret input box (password box, hidden field, verification code, etc.): the page settings are
     * set and do not change with the filled content. The other properties on the interface read out as
     * `***`: valueAsNumber, valueAsDate, selectionStart/selectionEnd, textLength, validity,
     * validationMessage, and those newly added by browsers later; the methods are still packaged, and
     * whether they can be called is left to [READ_METHODS]. Value and textContent are different types.
     * There is no value for `***` or it is empty.
     */
    val SECRET_FIELD_PROPS = listOf(
        "type", "name", "form", "labels", "list", "placeholder", "autocomplete", "disabled", "readOnly", "required", "willValidate",
        "maxLength", "minLength", "size", "pattern", "min", "max", "step", "multiple", "dirName", "cols", "rows", "wrap",
        "formAction", "formEnctype", "formMethod", "formNoValidate", "formTarget",
    )

    /** The built-in objects (together with prototypes) that are frozen and then handed over to the model, and the global names are also set to read-only: the model cannot change them, so it cannot intercept the originals passed in the package and cannot replace the functions used for checking. */
    private val FROZEN = PageScriptProtocol.ALLOWED_GLOBALS.filter { it !in setOf("undefined", "NaN", "Infinity") } + listOf("URL", "URLSearchParams")

    /** Before the first line of the model code, there are several lines of compiled source code (`(async function anonymous(…`, `) {`, `'use strict';`, `return (` or blank lines). */
    private const val LINE_OFFSET = 4

    private fun js(v: Collection<String>): String = v.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    private fun jsMap(m: Map<String, List<String>>): String = m.entries.joinToString(",", "{", "}") { (k, v) -> JsonPrimitive(k).toString() + ":" + js(v) }

    /** Install the pre-script to read the world: freeze built-in objects, register callable methods, define packaging, `__minaReadRun` and `__minaReadStop`. Pretend and pretend not to do anything. */
    val PRELUDE: String = """
(() => {
  'use strict';
  const G = globalThis, O = Object, R = Reflect;
  if (O.prototype.hasOwnProperty.call(G, '__minaReadRun')) return;
  const AsyncFunction = O.getPrototypeOf(async function () {}).constructor;
  const fnBind = G.Function.prototype.bind;
  const apply = R.apply, rget = R.get, ownKeys = R.ownKeys, getDesc = O.getOwnPropertyDescriptor, getProto = O.getPrototypeOf;
  const freeze = O.freeze, isArray = Array.isArray, keysOf = O.keys, define = O.defineProperty, names = O.getOwnPropertyNames;
  const stringify = JSON.stringify, parseJson = JSON.parse, timer = G.setTimeout;
  const URL_ = G.URL, loc = G.location, doc = G.document;
  const Elem = G.Element, Input = G.HTMLInputElement, Area = G.HTMLTextAreaElement;
  const MASK = '$MASK';
  const fixed = (o, k, v) => { try { define(o, k, { value: v, writable: false, enumerable: false, configurable: false }); } catch (e) {} };
  const fail = (code, message) => { const e = new Error(code + ': ' + message); e.code = code; return e; };
  const deny = (what) => { throw fail('read_only', what + ' is not allowed in a read script; it can only read the page'); };
  const NETWORK = new Set(${js(NETWORK_NAMES)});
  const offline = () => { throw fail('blocked_network', ${JsonPrimitive(NETWORK_MESSAGE)}); };

  // Use strings as a path for code execution (it will run in the global scope) and block the call stack
  // API.
  const unavailable = function () { throw fail('read_only', 'running code from strings is not available'); };
  const fnKinds = [function () {}, async function () {}, function* () {}, async function* () {}];
  for (const f of fnKinds) fixed(getProto(f), 'constructor', unavailable);
  fixed(G, 'eval', unavailable);
  fixed(G, 'Function', unavailable);
  fixed(G.Error, 'prepareStackTrace', undefined);

  // Freezes built-in objects that are touched by the model
  const ice = (x) => { if (x && (typeof x === 'object' || typeof x === 'function')) { try { freeze(x); } catch (e) {} } };
  // The global name is also set to read-only: otherwise the model code rewriting String will invalidate
  // the following check, and the same will be true for subsequent read scripts running in this world.
  for (const n of ${js(FROZEN)}) { const v = G[n]; ice(v); if (v && typeof v === 'function') ice(v.prototype); if (v !== undefined) fixed(G, n, v); }
  for (const f of fnKinds) ice(getProto(f));
  const arrayIter = getProto([][Symbol.iterator]());
  for (const p of [arrayIter, getProto(arrayIter), getProto(new Map().entries()), getProto(new Set().values()), getProto(''[Symbol.iterator]())]) ice(p);

  // Native functions that can be called via wrappers
  const SAFE = new WeakSet();
  const READ = ${jsMap(READ_METHODS)};
  for (const iface of keysOf(READ)) {
    const C = G[iface];
    const p = C && C.prototype;
    if (!p) continue;
    for (const k of READ[iface]) { const d = getDesc(p, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
    const it = getDesc(p, Symbol.iterator);
    if (it && typeof it.value === 'function') SAFE.add(it.value);
  }
  for (const k of ${js(GLOBAL_READ_METHODS)}) { const d = getDesc(G, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
  for (const k of ['toString', 'valueOf']) { const d = getDesc(loc, k); if (d && typeof d.value === 'function') SAFE.add(d.value); }
  for (const f of [Array.prototype.values, Array.prototype.keys, Array.prototype.entries, Array.prototype.forEach, arrayIter.next,
    O.prototype.toString, O.prototype.toLocaleString, O.prototype.valueOf, O.prototype.hasOwnProperty, O.prototype.propertyIsEnumerable]) SAFE.add(f);
  // The first parameter is the selector; the first few parameters are the attribute names.
  const SELECTORS = new WeakSet();
  for (const iface of ['Element', 'Document', 'DocumentFragment']) {
    const p = G[iface] && G[iface].prototype;
    if (p) for (const k of ['querySelector', 'querySelectorAll', 'closest', 'matches', 'webkitMatchesSelector']) { const d = getDesc(p, k); if (d && typeof d.value === 'function') SELECTORS.add(d.value); }
  }
  const ATTRS = new WeakMap();
  for (const [k, i] of [['getAttribute', 0], ['getAttributeNS', 1]]) { const d = Elem && getDesc(Elem.prototype, k); if (d && typeof d.value === 'function') ATTRS.set(d.value, i); }
${SelectorPolicy.JS}  const DENIED = new Set(${js(DENIED_PROPS)});

  // Input boxes that do not read values: password boxes, hidden fields, autocomplete marked with
  // passwords, bank cards, and one-time verification codes
  const SECRET_AC = new Set(${js(PageScriptProtocol.SECRET_AUTOCOMPLETE)});
  const SECRET_TYPES = new Set(${js(PageScriptProtocol.SECRET_INPUT_TYPES)});
  // Which kind of node is it? Use the native getter to read it, without using instanceof: The nodes in
  // the same-origin iframe belong to that framework’s own set of interfaces. Instanceof cannot recognize
  // it, but the native getter still recognizes it.
  const getterOf = (C, k) => { const d = C && getDesc(C.prototype, k); return d && d.get; };
  const nodeTypeOf = getterOf(G.Node, 'nodeType'), localNameOf = getterOf(Elem, 'localName'), shadowHostOf = getterOf(G.ShadowRoot, 'host');
  const native = (g, x) => { if (!g || x === null || typeof x !== 'object') return undefined; try { return apply(g, x, []); } catch (e) { return undefined; } };
  // The node type is recorded by object (the type of the same object will not change): before reading
  // the attributes, the type must be recognized first. The native getter of nodeType on objects that are
  // not nodes (collections, rectangles, styles, etc.) throws an error every time, and the same object is
  // only recognized once.
  const kinds = new WeakMap();
  const typeOf = (x) => {
    if (x === null || typeof x !== 'object') return undefined;
    let t = kinds.get(x);
    if (t === undefined) { t = native(nodeTypeOf, x) || 0; kinds.set(x, t); }
    return t || undefined;
  };
  const tagOf = (x) => (typeOf(x) === 1 ? native(localNameOf, x) : undefined);
  const secretNow = (el) => {
    const tag = tagOf(el);
    if (tag === 'input') {
      if (SECRET_TYPES.has(String(el.type).toLowerCase())) return true;
    } else if (tag !== 'textarea') return false;
    const ac = el.getAttribute('autocomplete');
    return !!ac && ac.toLowerCase().split(/\s+/).some((t) => SECRET_AC.has(t));
  };
  // What used to be a secret input box (such as changing the type to text in "Show Password") has always
  // been regarded as a secret: starting from the time when the world was read and built.
  const wasSecret = new WeakSet();
  const markIn = (root) => { try { if (secretNow(root)) wasSecret.add(root); for (const x of root.querySelectorAll('input, textarea')) if (secretNow(x)) wasSecret.add(x); } catch (e) {} };
  try {
    new G.MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'attributes') { if (secretNow(r.target)) wasSecret.add(r.target); } else for (const n of r.addedNodes) if (n.nodeType === 1) markIn(n);
      }
    }).observe(doc, { subtree: true, childList: true, attributes: true, attributeFilter: ['type', 'autocomplete'] });
  } catch (e) {}
  markIn(doc);
  const secret = (el) => secretNow(el) || (!!el && typeof el === 'object' && wasSecret.has(el));
  const SECRET_TEXT = new Set(['value', 'defaultValue', 'textContent', 'innerText', 'outerText', 'innerHTML', 'nodeValue']);
  // The properties of the secret input box's own interface: only the page is set and will not change
  // with the content, and the rest (including the new value reading interface added by the browser in
  // the future) are ***
  const FIELD_SAFE = new Set(${js(SECRET_FIELD_PROPS)});
  const fieldApi = (el, k) => { const tag = tagOf(el); return !!((tag === 'input' && Input && getDesc(Input.prototype, k)) || (tag === 'textarea' && Area && getDesc(Area.prototype, k))); };
  // InnerHTML / outerHTML will write the content of the input box:template, but querySelectorAll cannot
  // find it, so go in and find it separately. Originals and copies are listed in the same order. No
  // recursion (template can have many layers); only the HTML template has content (element with the same
  // name in SVG does not); Use native methods to search (if there is a control called querySelectorAll
  // in the form, it will still be found)
  const contentOf = getterOf(G.HTMLTemplateElement, 'content');
  const QSA = { 1: Elem && Elem.prototype.querySelectorAll, 9: G.Document && G.Document.prototype.querySelectorAll, 11: G.DocumentFragment && G.DocumentFragment.prototype.querySelectorAll };
  const fields = (node, out) => {
    const todo = [node];
    const inner = (t) => { const c = tagOf(t) === 'template' && native(contentOf, t); if (c) todo.push(c); };
    for (let i = 0; i < todo.length; i++) {
      const n = todo[i], find = QSA[typeOf(n)];
      inner(n);
      if (find) for (const x of apply(find, n, ['input, textarea, template'])) { if (tagOf(x) === 'template') inner(x); else out.push(x); }
    }
    return out;
  };
  // When the value of the secret input box is included in the HTML, code it on the copy and then
  // retrieve it. Which box to code looks at the original (the copy has no record of "used to be a secret
  // input box"), and the box at the same position in the copy is coded. The copy is placed in an empty
  // document that does not belong to any window (create it once and use it later): custom elements are
  // not constructed, page code does not run, and resources are not loaded. ShadowRoot cannot be copied,
  // put its child nodes into a div. The format written in this way is different from the page itself in
  // two ways, which does not affect coding: the content in noscript will be escaped; XHTML pages are
  // written in HTML format
  let inert = null;
  // A coded copy; it is null when there is no secret input box in v, and it is MASK when the copy does
  // not match the original.
  const masked = (v) => {
    const root = typeOf(v) !== 1;
    const orig = fields(v, root ? [] : [v]);
    let hit = false;
    for (const x of orig) if (secret(x)) { hit = true; break; }
    if (!hit) return null;
    const d = inert || (inert = doc.implementation.createHTMLDocument(''));
    let c;
    if (root) { c = d.createElement('div'); for (const n of v.childNodes) c.appendChild(d.importNode(n, true)); } else c = d.importNode(v, true);
    const copy = fields(c, root ? [] : [c]);
    if (copy.length !== orig.length) return MASK;
    for (let i = 0; i < copy.length; i++) {
      if (!secret(orig[i])) continue;
      const x = copy[i];
      if (x.hasAttribute('value')) x.setAttribute('value', MASK);
      if (tagOf(x) === 'textarea' && x.textContent) x.textContent = MASK;
    }
    return c;
  };
  const html = (v, k) => { const c = masked(v); return c === null ? rget(v, k, v) : c === MASK ? MASK : rget(c, k, c); };
  // The words in the secret textarea are their initial values (this is where the page writes the typed
  // words back to their initial values). The textContent of the outer element and shadow root is
  // brought; the innerText of the unrendered outer element is also brought (in this case, innerText is
  // textContent); when the textarea is in the option, the rendered innerText is also brought (the option
  // is written according to its text); a. The text of the option, the label and value of the option, and
  // the value and defaultValue of the output are also calculated according to the text of the
  // descendants. These changes should be coded on the copy before being retrieved. The innerText of
  // other rendered elements does not contain the words in the textarea and is given as it is.
  const textOf = getterOf(G.Node, 'textContent'), parentOf = getterOf(G.Node, 'parentNode'), closest = Elem && Elem.prototype.closest;
  // Use Map: the attribute name can be constructor or __proto__
  const DESC = new Map([['text', new Set(['a', 'option'])], ['label', new Set(['option'])], ['value', new Set(['option', 'output'])], ['defaultValue', new Set(['output'])]]);
  // The secret textarea with words in v: 0 if not, 1 if there is, and 2 if it is in option
  const areasIn = (v) => {
    const find = QSA[typeOf(v)];
    let hit = 0;
    if (find) for (const a of apply(find, v, ['textarea'])) if (secret(a) && native(textOf, a)) { hit = 1; if (closest && apply(closest, a, ['option'])) return 2; }
    return hit;
  };
  const text = (v, k) => {
    const x = rget(v, k, v);
    if (!x || typeof x !== 'string') return x;
    const hit = areasIn(v), shown = k === 'innerText' || k === 'outerText';
    if (!hit || (shown && hit === 1 && x !== native(textOf, v))) return x;
    const c = masked(v);
    return c === null ? x : c === MASK ? MASK : DESC.has(k) ? rget(c, k, c) : native(textOf, c);
  };
  // Write down the secret text nodes you have obtained, and then keep counting them as secrets: the
  // child text nodes of the secret textarea (it may also be a CDATA segment in the XML page, which is
  // also a text node and is also counted in the value), and the value attribute node of the secret input
  // box. When the page replaces the words in the textarea (writes the typed words back to the initial
  // value) and deletes the value attribute, the old node is separated from the document, but the words
  // are still on it. Also note which box the node is in: when the box is handed over to the model, it is
  // not secret, but later becomes secret, words are written into the same node, and when the node is no
  // longer in that box (it is separated from the document or moved to other elements), the box is
  // calculated according to that box.
  const held = new WeakSet(), ownerOf = new WeakMap();
  const HELD_TEXT = new Set(['data', 'nodeValue', 'textContent', 'wholeText', 'length', 'value']);
  const substringData = G.CharacterData && G.CharacterData.prototype.substringData;
  const holds = (v) => {
    if (held.has(v)) return true;
    const t = typeOf(v);
    let p;
    if (t === 3 || t === 4) { p = native(parentOf, v); if (tagOf(p) !== 'textarea') p = undefined; }
    else if (t === 2) { if (String(v.name).toLowerCase() !== 'value') return false; p = v.ownerElement; }
    else return false;
    if (p) ownerOf.set(v, p); else p = ownerOf.get(v);
    if (!p || !secret(p)) return false;
    held.add(v);
    return true;
  };
  const readProp = (v, k) => {
    if (typeof k === 'string') {
      if (DENIED.has(k)) deny('reading ' + k);
      if ((k === 'outerHTML' || k === 'innerHTML') && (typeOf(v) === 1 || (k === 'innerHTML' && native(shadowHostOf, v) !== undefined))) return html(v, k);
      if (SECRET_TEXT.has(k) && secret(v)) { const x = rget(v, k, v); return x ? MASK : x; }
      if (!FIELD_SAFE.has(k) && fieldApi(v, k) && secret(v)) { const x = rget(v, k, v); return typeof x === 'function' ? x : MASK; }
      if (HELD_TEXT.has(k) && holds(v)) { const x = rget(v, k, v); return x ? MASK : x; }
      if (k === 'textContent' || k === 'innerText' || k === 'outerText') return text(v, k);
      const tags = DESC.get(k);
      if (tags !== undefined) {
        const tag = tagOf(v);
        // The value of select is the value of the option selected on the page (it may not be the one
        // selected in the copy)
        if (tag === 'select' && k === 'value' && areasIn(v)) { const i = rget(v, 'selectedIndex', v); return i < 0 ? '' : readProp(rget(v, 'options', v)[i], k); }
        if (tags.has(tag)) return text(v, k);
      }
    }
    return rget(v, k, v);
  };

  // Packaging: The model only has the packaging in hand, and the original is only handed over to the
  // native function.
  const toProxy = new WeakMap(), toReal = new WeakMap();
  const wrap = (v, depth) => {
    if (v === null || (typeof v !== 'object' && typeof v !== 'function')) return v;
    if (toReal.has(v)) return v;
    const d = (depth || 0) + 1;
    if (d <= 4 && isArray(v)) { const out = []; for (let i = 0; i < v.length; i++) out.push(wrap(v[i], d)); return out; }
    if (d <= 4 && typeof v === 'object') {
      const proto = getProto(v);
      if (proto === O.prototype || proto === null) {
        const out = {};
        for (const k of keysOf(v)) define(out, k, { value: wrap(v[k], d), enumerable: true, writable: true, configurable: true });
        return out;
      }
    }
    let p = toProxy.get(v);
    if (!p) { holds(v); p = makeProxy(v); toProxy.set(v, p); toReal.set(p, v); }
    return p;
  };
  const toNative = (a) => {
    if (a === null || (typeof a !== 'object' && typeof a !== 'function')) return a;
    const r = toReal.get(a);
    // The wrapped function is handed over as it is: anyone who adjusts it must go through inspection.
    if (r !== undefined) return typeof r === 'function' ? a : r;
    // The model's own callback: the things passed to it by the native function are first packaged
    if (typeof a === 'function') return function () { const xs = []; for (let i = 0; i < arguments.length; i++) xs.push(wrap(arguments[i])); return toNative(apply(a, undefined, xs)); };
    return a;
  };
  const nameOf = (f) => { try { return String(f.name || ''); } catch (e) { return ''; } };
  const call = (f, self, xs) => {
    if (!SAFE.has(f)) { if (NETWORK.has(nameOf(f))) offline(); deny('calling ' + (nameOf(f) || 'this function')); }
    const t = toNative(self);
    if (f === substringData && holds(t)) return MASK;
    const args = [];
    for (const x of xs) args.push(toNative(x));
    // Selectors and attribute names are first changed to strings, and only changed once: the toString of
    // the object can be different each time, and the one checked must be the one handed to the browser.
    if (SELECTORS.has(f) && args.length) {
      args[0] = String(args[0]);
      if (selectsByValue(args[0])) throw fail('invalid_selector', 'selecting by the value attribute is not allowed; read the value property instead');
    }
    const at = ATTRS.get(f);
    if (at !== undefined && at < args.length) args[at] = String(args[at]);
    if (at !== undefined && secret(t) && String(args[at]).toLowerCase() === 'value') { const x = apply(f, t, args); return x ? MASK : x; }
    return wrap(apply(f, t, args));
  };
  const changing = () => deny('changing the page');
  // The packaging of the function uses a bound ordinary function as the basis: it can be used by new
  // (you can only get construct, please tell me why it doesn't work), and it does not have its own
  // prototype (it does not hinder the constraints of the proxy)
  const makeProxy = (v) => new Proxy(typeof v === 'function' ? apply(fnBind, function () {}, [undefined]) : {}, {
    get: (_, k) => wrap(readProp(v, k)),
    set: changing, defineProperty: changing, deleteProperty: changing, setPrototypeOf: changing, preventExtensions: changing,
    getPrototypeOf: () => null,
    has: (_, k) => k in v,
    ownKeys: () => { const out = []; for (const k of ownKeys(v)) if (!(typeof k === 'string' && DENIED.has(k))) out.push(k); return out; },
    getOwnPropertyDescriptor: (_, k) => {
      if (typeof k === 'string' && DENIED.has(k)) return undefined;
      const d = getDesc(v, k);
      return d ? { value: wrap(readProp(v, k)), writable: false, enumerable: d.enumerable, configurable: true } : undefined;
    },
    apply: (_, self, xs) => call(v, self, xs),
    construct: () => { if (typeof v === 'function' && NETWORK.has(nameOf(v))) offline(); deny('creating page objects'); },
  });

  // Names that can be used for model code: pure ECMAScript built-in + PROVIDED, other global names are
  // covered
  const PROVIDED = ${js(PROVIDED)};
  const ALLOWED = new Set(${js(PageScriptProtocol.ALLOWED_GLOBALS)}.concat(PROVIDED));
  const RESERVED = new Set(${js(PageScriptProtocol.RESERVED)});
  const hidden = new Set(['globalThis', 'self', 'top', 'parent', 'frames', 'opener', '__minaReadRun', '__minaReadStop']);
  for (let o = G; o; o = getProto(o)) for (const k of names(o)) {
    if (/^[A-Za-z_$][\w$]*$/.test(k) && !ALLOWED.has(k) && !RESERVED.has(k)) hidden.add(k);
  }
  const shadow = [...hidden];
  const blanks = shadow.map(() => undefined);
  const params = shadow.concat(PROVIDED);
  const state = {};
  const pageWindow = wrap(G), pageDocument = wrap(doc), pageLocation = wrap(loc), computedStyle = wrap(G.getComputedStyle);
  let generation = 0;
  const STACK_LINE = /mina-read\.js:(\d+):\d+/;
  const KNOWN = new Set(['read_only', 'invalid_selector', 'blocked_network', 'limit_exceeded', 'network_error', 'too_large', 'invalid_arguments', 'script_ended']);
  const failure = (e) => {
    const m = STACK_LINE.exec(String((e && e.stack) || ''));
    const line = m ? Number(m[1]) - $LINE_OFFSET : 0;
    const code = e && typeof e.code === 'string' && KNOWN.has(e.code) ? e.code : 'script_error';
    return { ok: false, error: code, message: String((e && e.message) || e), line: line > 0 ? line : null };
  };

  fixed(G, '__minaReadStop', () => { generation++; });
  fixed(G, '__minaReadRun', (code, args, lim) => {
    const gen = ++generation;
    const live = () => { if (gen !== generation) throw fail('script_ended', 'the script was stopped'); };
    // Do not touch the Internet: you want the data behind the page and read the response received by the
    // page (web_capture)
    const pageFetch = () => { live(); offline(); };
    const sleep = (ms) => { live(); return new Promise((done) => timer(done, Math.max(0, Math.min(Number(ms) || 0, lim.sleepMs)))); };
    let fn;
    try {
      fn = new AsyncFunction(...params, "'use strict';\nreturn (\n" + code + '\n);\n//# sourceURL=$SOURCE_URL');
    } catch (e) {
      try {
        fn = new AsyncFunction(...params, "'use strict';\n\n" + code + '\n//# sourceURL=$SOURCE_URL');
      } catch (e2) {
        return Promise.resolve(failure(e2));
      }
    }
    const input = args == null ? undefined : parseJson(stringify(args));
    const ice2 = (x) => { if (x && typeof x === 'object') { for (const k of keysOf(x)) ice2(x[k]); freeze(x); } return x; };
    let running;
    try {
      running = apply(fn, undefined, blanks.concat([pageDocument, pageWindow, pageLocation, pageFetch, state, ice2(input), sleep, computedStyle, URL_, G.URLSearchParams]));
    } catch (e) {
      return Promise.resolve(failure(e));
    }
    return running.then((v) => {
      let s;
      try { s = v === undefined ? 'null' : stringify(v); } catch (e) { return { ok: false, error: 'script_error', message: 'the result is not JSON-serializable: ' + String((e && e.message) || e), line: null }; }
      if (s === undefined) s = 'null';
      const max = lim.resultChars;
      return { ok: true, json: s.length > max ? s.slice(0, max) : s, truncated: s.length > max };
    }, failure);
  });
})();
//# sourceURL=$PRELUDE_URL
""".trimStart()

    /** Run the expression of the reading script once (evaluate in the reading world, wait for Promise, and return by value). [args] is the parameter of the site command. */
    fun run(code: String, args: JsonObject?, resultChars: Int): String =
        "globalThis.__minaReadRun(${JsonPrimitive(code)}, ${args ?: "null"}, " +
            "{resultChars: $resultChars, sleepMs: $READ_SLEEP_MAX_MS})"

    /** After the time limit is reached, sleep after the script that is still running will fail. */
    const val STOP = "globalThis.__minaReadStop && globalThis.__minaReadStop()"

    /** Returns the source of the page (location.origin) when the world is loaded. If not, it is null. */
    const val PROBE = "typeof globalThis.__minaReadRun === 'function' ? String(location.origin) : null"

    /** There is a reading script or its pre-script in the initiating call stack (including the asynchronous parent chain). */
    fun fromScript(params: JsonObject): Boolean {
        var stack = (params["initiator"] as? JsonObject)?.get("stack") as? JsonObject
        var depth = 0
        while (stack != null && depth++ < 32) {
            val frames = stack["callFrames"] as? JsonArray
            if (frames != null && frames.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull in SCRIPT_URLS }) return true
            stack = stack["parent"] as? JsonObject
        }
        return false
    }

    private val SCRIPT_URLS = setOf(SOURCE_URL, PRELUDE_URL)

    /**
     * Bottom line: `Network.requestWillBeSent` is a request sent by the reading script (or its
     * pre-script) and returns a description: Reading the script does not touch the network, and any
     * request will not work. If the script is not read, null is sent.
     */
    fun requestViolation(params: JsonObject): String? {
        if (!fromScript(params)) return null
        val request = params["request"] as? JsonObject
        val url = (request?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val method = (request?.get("method") as? JsonPrimitive)?.contentOrNull ?: "GET"
        return "the script sent a $method request (${url.take(200)}); $NETWORK_MESSAGE"
    }

    /** `scheme://host[:port]`, the default port is not written; if it cannot be read, it is null. */
    fun originOf(url: String): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = u.port.takeIf { it >= 0 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    /**
     * `__minaReadRun` Object returned by value → result. As a result, it is first desensitized according
     * to the rules of web data (name fields, JWT, long and complex strings, Key), and then cut off to
     * [EVAL_RESULT_MAX_CHARS]. [fresh]: This time, a new reading world was created (the previous ones
     * stored in the state are gone).
     */
    fun result(value: JsonObject?, fresh: Boolean): EvalJs {
        value ?: return EvalJs.Failed("script_error", "the script produced no readable result")
        fun str(k: String) = (value[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((value["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
            val line = (value["line"] as? JsonPrimitive)?.intOrNull
            return EvalJs.Failed(str("error") ?: "script_error", (line?.let { "line $it: " } ?: "") + str("message").orEmpty())
        }
        val json = Redactor.pageData(str("json") ?: "null")
        val truncated = (value["truncated"] as? JsonPrimitive)?.booleanOrNull == true || json.length > EVAL_RESULT_MAX_CHARS
        return EvalJs.Ok(json.take(EVAL_RESULT_MAX_CHARS), truncated, fresh)
    }
}
