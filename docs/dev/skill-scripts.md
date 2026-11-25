# sandbox script

Skills and website knowledge can be brought with scripts and placed under `scripts/` in their
respective directories. After packaging, these directories appear under `/opt/mina/skills/` in the
sandbox, and are run by `run_bundled_script` in the sandbox. parameter:

- `source`: The directory where the script is located, that is, `scriptSource` given in the results
  of `knowledge_read` and `skill_read`, such as `skills/documents`;
- `script`: The script path in the directory, such as `scripts/extract_pdf.py`;
- `args`: Parameter array (up to 50), handed to the script as it is, without passing through the
  shell;
- `timeoutSec`: time limit, 1–600 seconds, default 60.

The interpreter is selected by extension: python3 is used for `.py`, node is used for `.mjs` and
`.js`, and sh(`ScriptManifest.interpreterFor`) is used for `.sh`.

There is currently a script in the repository:

| script | Location | do what | what is needed |
|---|---|---|---|
| `scripts/extract_pdf.py` | `skills/documents/` | You can extract words from text-based PDF by page, view metadata, and write files; scanned files will not be processed. | Alpine bag `py3-pypdf` |

The above locations are all under `core/src/main/resources/`. The scripts all carry `--help`. When
an error occurs, they exit with a non-zero value and write a reason on the standard error.

## `scripts/manifest.json`

Each directory with scripts has one copy of `scripts/manifest.json`, which is an array, one item for
each script:

```json
[
  {"file": "scripts/extract_pdf.py", "runnable": true, "needs": ["apk:py3-pypdf"]}
]
```

- `file`: The path of the script relative to the directory; items that are not relative paths within
  the directory will be discarded.
- `runnable`: Can it run in a sandbox? The script of `false` is only used to indicate what is
  missing, `run_bundled_script` will reject it.
- `reason`: Optional, the reason given when `runnable` is `false`.
- `needs`: dependency list. `apk:<package>` means that this Alpine package is needed (must be in
  `SANDBOX_PACKAGES`); `net` means that it needs to be connected to the Internet.
- `network`: Do you need to connect to the Internet? Set for `true` → When "Allow script networking"
  in Advanced is turned off (off by default), `run_bundled_script` will refuse to run and prompt to
  open it.

The judgment of analysis and "whether it can run at this moment" is made by
`core/.../knowledge/ScriptManifest.kt`; whether the scripts mentioned in the list and documents
match the script files are checked by `BundledScriptsManifestTest`.

## Operating environment

The sandbox is based on Alpine 3.22, `SANDBOX_PACKAGES` (`core/.../cap/sandbox/SandboxPackages.kt`)
lists the software packages that come with the APK: python3, nodejs, py3-pillow, py3-pypdf, tzdata.
Among them, py3-pypdf is used for PDF word extraction scripts; py3-pillow is currently not used by
built-in skill scripts, so the package itself is reserved for now; tzdata allows the command to
process local time according to the current time zone of the mobile phone. These packages are loaded
into the running environment that comes with the APK (`tools/sandbox/build-runtime.py`, see
`tools/sandbox/README.md`) when building; when the package changes, the version of the running
environment changes accordingly, and the phone will automatically re-unpack it when it is started.


