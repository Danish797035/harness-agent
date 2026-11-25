# Sandboxed Native Programs: Sources, Licenses and Source Code Release

Harness's sandbox comes with three executable files, all in `app/src/main/jniLibs/arm64-v8a/`, which
are installed with the APK into `nativeLibraryDir`. Harness only runs them as child processes and
does not link them.

| document | what is | source | Version | license |
|---|---|---|---|---|
| `libproot.so` | PRoot ontology, static link talloc | For the `proot` source code package in PRoot:`sources.lock`, add `patches/proot-*.patch`; for the `talloc` source code package in talloc:`sources.lock`, only `talloc.c` is compiled without modification. | PRoot 5.4.0、talloc 2.4.3 | GPL-2.0-or-later(PRoot)、LGPL-3.0-or-later(talloc) |
| `libproot_loader.so` | PRoot's loader (static, `-nostdlib`) | The same PRoot source code package | 5.4.0 | GPL-2.0-or-later |
| `libharnessrun.so` | Harness's sandbox launcher (timeout, network disconnection, cleanup) | `app/src/main/cpp/harnessrun/harnessrun.c` | With Harness | MIT |

The full text of the license and copyright statement (`licenses/THIRD_PARTY_NOTICES.md`) are located
at `licenses/` in the root directory of the repository.

## Comes with its own operating environment

The running environment (Alpine 3.22 minimal root file system + Python, Node and other software
packages) is released with the APK at `app/src/main/assets/runtime/rootfs.tar.xz`. When Harness is
started for the first time on the phone, `files/sandbox/rootfs` is detected in the background and is
not connected to the Internet.

- `runtime/sources.lock`: The address and SHA-256 of the minimum root file system, as well as the
  repository, version, APKINDEX check value of each software package, and the SHA-256 of `.apk`.
- `build-runtime.py`: Press `sources.lock` to download, verify and spell it into `rootfs.tar.xz`. Do
  not run the apk throughout the process: Unpack the software package according to the layout of the
  apk, and use the metadata signed by the software package to write `/lib/apk/db/installed`, so the
  database seen by the apk in the running environment is consistent. Each `.apk` must check: the
  SHA-256 in the lock file, its own RSA signature (the public key is taken from the pinned minimum
  root file system), the control segment checksum is consistent with APKINDEX, and the data segment
  is consistent with `datahash` in the signed `.PKGINFO`. The installation scripts and triggers that
  will be executed on the mobile phone must be listed in `SCRIPTS_NOT_RUN` one by one (pin according
  to the SHA-256 of the content, and it is correct to state why it does not run), otherwise the
  build will fail. Generates both `app/src/main/java/com/meenai/mina/cap/sandbox/BundledRuntime.kt`
  (version, SHA-256, size) and `app/src/main/assets/runtime/packages.json` (all packages, versions,
  licenses; the "Open Source License" page is generated from it).
- The product is reproducible byte by byte:

```bash
python3 tools/sandbox/build-runtime.py --resolve   # after changing the Alpine point release or packages, re-resolve dependencies and rewrite runtime/sources.lock
python3 tools/sandbox/build-runtime.py             # generate rootfs.tar.xz, BundledRuntime.kt and packages.json
python3 tools/sandbox/build-runtime.py --check     # regenerate and compare with the artifacts in the repo; exit 1 on a mismatch
```

`WANT` in `build-runtime.py` must be consistent with `SANDBOX_PACKAGES` in core (check
`BundledRuntimeTest`).

The operating environment contains `tzdata`. Every time a command is started, `ProcRunner.env`
writes the current IANA time zone of the phone to `TZ`, making the local time of the command line,
Python and Node consistent with the phone, and retaining daylight saving time and historical offset
rules; after the phone switches time zones, the next command is updated accordingly.

## document

- `sources.lock`: `Name Version Download Address SHA-256` per line. Only use the source code
  packages listed here to compile and build source code packages. Check SHA-256 first after
  downloading.
- `patches/`: Modifications to the upstream source code, the file name is `<name>-<number>-<description>.patch`,
  typed in `patch -p1` in the order of file names. Patches follow the license of the component being
  modified.
  - `proot-0001-exitkill.patch`: Add `PTRACE_O_EXITKILL` to PRoot's tracking options. In this way,
    when PRoot exits (even if it is killed by SIGKILL), the kernel will kill all tracked processes,
    and the commands in the sandbox will not continue to run in the background.
  - `proot-0002-seccomp-trap-enosys.patch`: Android's seccomp policy for apps sends SIGSYS on newer
    syscalls (`io_uring`, `clone3`, `faccessat2`, `epoll_pwait2`, `rseq`, etc.) ahead of any later
    filter, killing sandboxed programs that probe them. PRoot changes the return value to `-ENOSYS` at
    the signal stop of this SIGSYS (`si_code == SYS_SECCOMP`) and cancels the signal, so the program
    uses its fallback path; `faccessat2` with only `AT_EACCESS` on arm64 is rewritten to `faccessat`
    (the app process's real and effective identities are always identical, so both are equivalent), and
    permission checks remain handled by the kernel.
  - `proot-0003-arm64-new-path-syscalls.patch`: The arm64 call number table of PRoot 5.4.0 only
    reaches statx(291), and PRoot cannot recognize the new calls after that. When the platform
    releases them, they go directly to the kernel with unconverted paths and are executed according
    to the host path (for example, musl's `faccessat(…, AT_EACCESS)` tries faccessat2 first and
    determines the wrong file). Fill in these numbers and add them to PRoot's seccomp tracking list:
    faccessat2, fchmodat2 and faccessat, fchmodat conversion paths; execveat (will bypass execve
    processing), openat2 (`RESOLVE_*` The rules are for the directory tree in the sandbox),
    io_uring_setup (path operations without system calls), new mounting interfaces (open_tree,
    move_mount, fspick, mount_setattr, open_tree_attr) and setxattrat. *xattrat,
    file_getattr/file_setattr all return `-ENOSYS`, and the caller returns the old call, which is
    converted normally by PRoot. At the same time, the out-of-bounds judgment when looking up the
    table is corrected (`index > length` is changed to `>=`).
  - `proot-0004-link2symlink-fstat-nonfile.patch`: link2symlink extension uses the `/proc` link of
    the descriptor as the path lstat when fstat returns; pipe, socket, etc. (`pipe:[…]`). After
    lstat fails, the original code continues to read the uninitialized stat structure, and changes
    the successful fstat to -1 (EPERM) depending on the residual value on the stack. Programs that
    check standard descriptors on startup (node will abort directly) therefore fail randomly.
    Changed to not processing lstat failure; `" (deleted)"` suffix is only checked after reading
    the link; when the link target disappears, -errno is returned instead of -1.
- `build-native.sh`: Download the source code from `sources.lock`, check SHA-256, apply patches, and
  cross-compile the three programs with NDK 28.2.13676358 (API 30).

## Recompile

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
tools/sandbox/build-native.sh           # build and overwrite the artifacts in app/src/main/jniLibs/arm64-v8a/
tools/sandbox/build-native.sh --check   # rebuild only and compare SHA-256 with the repo artifacts; exit 1 on a mismatch
```

Path prefix mapping will be done during compilation, and build-id will not be written, so products
compiled by the same NDK will be the same byte by byte. Use `--check` to confirm that the binaries
in the warehouse indeed come from these source codes.

## Publish the source code package when publishing the APK

Every time you release an APK, you must package the source code package, `patches/`, compilation
script and license listed in `sources.lock` into `harness-agent-<version>-gpl-sources.tar.gz`, and put it
on the same release page as the APK. Run in the root directory of the repository:

```bash
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
NAME=harness-agent-$VERSION-gpl-sources
OUT=build/gpl-sources/$NAME
rm -rf "$OUT" && mkdir -p "$OUT"
while read -r name version url sum; do
  case "$name" in ''|'#'*) continue ;; esac
  curl -fsSL "$url" -o "$OUT/$name-$version.tar.gz"
  echo "$sum  $OUT/$name-$version.tar.gz" | shasum -a 256 -c -
done < tools/sandbox/sources.lock
cp -R tools/sandbox/patches tools/sandbox/sources.lock tools/sandbox/build-native.sh tools/sandbox/README.md "$OUT/"
cp licenses/GPL-2.0.txt licenses/GPL-3.0.txt licenses/LGPL-3.0.txt "$OUT/"
# for every GPL/LGPL/MPL package in the runtime: the Alpine build recipe (pinned to the aports commit in the package record) and the upstream source, each verified by SHA-512
python3 tools/sandbox/runtime-sources.py "$OUT/runtime"
cp tools/sandbox/build-runtime.py tools/sandbox/runtime-sources.py app/src/main/assets/runtime/packages.json "$OUT/runtime/"
cp -R tools/sandbox/runtime "$OUT/runtime/lock"
COPYFILE_DISABLE=1 tar -czf "build/gpl-sources/$NAME.tar.gz" -C build/gpl-sources "$NAME"
```

After running, you get `build/gpl-sources/harness-agent-<version>-gpl-sources.tar.gz`. Before uploading,
use `tar -tzf` to take a look and confirm that there are two source code packages, `patches/`, and
the directory of each GPL/LGPL/MPL software package under `runtime/` and `runtime/SOURCES.txt` (gcc
and other source codes are larger, the The whole package is about hundreds of MB).


