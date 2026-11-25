# Third-party notices

## scrcpy

- Project: https://github.com/Genymobile/scrcpy
- License: Apache-2.0
- Usage: The method of obtaining the system context (ActivityThread/Context disguised as package
  name) as shell is modeled on scrcpy. The source file header retains the source:
  - `app/src/main/java/com/meenai/mina/shell/ShellContext.kt`

## Apache Commons Compress

- Project: https://commons.apache.org/
- Artifact: `org.apache.commons:commons-compress:1.28.0`
- License: Apache-2.0
- Usage: Depends in the form of a library, tar.gz
  (`core/src/main/kotlin/com/meenai/mina/core/cap/sandbox/RootfsExtractor.kt`) for streaming
  unpacking of the sandbox root file system.
- NOTICE:

```
Apache Commons Compress
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons Codec

- Project: https://commons.apache.org/
- Artifact: `commons-codec:commons-codec:1.19.0`
- License: Apache-2.0
- Usage: Transitive dependency of Apache Commons Compress.
- NOTICE:

```
Apache Commons Codec
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons IO

- Project: https://commons.apache.org/
- Artifact: `commons-io:commons-io:2.20.0`
- License: Apache-2.0
- Usage: Transitive dependency of Apache Commons Compress.
- NOTICE:

```
Apache Commons IO
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons Lang

- Project: https://commons.apache.org/
- Artifact: `org.apache.commons:commons-lang3:3.18.0`
- License: Apache-2.0
- Usage: Transitive dependency of Apache Commons Compress.
- NOTICE:

```
Apache Commons Lang
Copyright 2001-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Kotlin standard library

- Project: https://kotlinlang.org/
- Artifacts:
`org.jetbrains.kotlin:kotlin-stdlib:2.1.20`、`kotlin-stdlib-common:2.1.20`、`kotlin-stdlib-jdk7:1.9.10`、
  `kotlin-stdlib-jdk8:1.9.10`;`org.jetbrains:annotations:23.0.0`
- License: Apache-2.0 (according to respective POM)
- Usage: Depend in the form of a library and enter APK.

## kotlinx.coroutines

- Project: https://github.com/Kotlin/kotlinx.coroutines
- Artifacts:
`org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`、`kotlinx-coroutines-core-jvm:1.9.0`、`kotlinx-coroutines-android:1.9.0`
- License: Apache-2.0 (according to respective POM)
- Usage: Depend in the form of a library and enter APK.

## kotlinx.serialization

- Project: https://github.com/Kotlin/kotlinx.serialization
- Artifacts: `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3`,
  `kotlinx-serialization-json:1.7.3` (and their `-jvm` variants)
- License: Apache-2.0 (according to respective POM)
- Usage: Depend in the form of a library and enter APK.

## OpenTelemetry

- Project: https://github.com/open-telemetry/opentelemetry-proto-java
- Artifacts: OTLP trace, common, resource and collector schemas from
  `io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha` (build-time only).
- License: Apache-2.0
- Usage: Generate lite OTLP trace messages.

## Protocol Buffers

- Project: https://github.com/protocolbuffers/protobuf
- Artifact: `com.google.protobuf:protobuf-javalite:4.36.2`
- License: BSD-3-Clause (see `app/src/main/assets/licenses/Protocol-Buffers.txt`).
- Usage: Official OTLP messages.

## OkHttp

- Project: https://square.github.io/okhttp/
- Artifact: `com.squareup.okhttp3:okhttp:4.12.0`
- License: Apache-2.0 (according to POM)
- Usage: Dependent in the form of a library, used to access the model interface.

## Okio

- Project: https://github.com/square/okio/
- Artifacts: `com.squareup.okio:okio:3.6.0`、`okio-jvm:3.6.0`
- License: Apache-2.0 (according to respective POM)
- Usage: OkHttp dependency.

## AndroidX (including Jetpack Compose)

- Project: https://developer.android.com/jetpack/androidx
- License: Apache-2.0 (according to respective POM; `androidx.compose:compose-bom:2024.12.01` is
  just a version list, not including code)
- Artifacts (release all AndroidX components in the runtime classpath):
  - Compose
1.7.6:`compose.runtime:runtime`、`runtime-saveable`;`compose.ui:ui`、`ui-geometry`、`ui-graphics`、`ui-text`、
`ui-unit`、`ui-util`;`compose.foundation:foundation`、`foundation-layout`;`compose.animation:animation`、`animation-core`;
`compose.material:material-icons-core`, `material-ripple` (each of the above contains `-android`
variant)
  - `compose.material3:material3:1.3.1`
  - `activity:activity`、`activity-ktx`、`activity-compose`:1.9.3
  - `lifecycle:*`(common、common-java8、livedata-core、process、runtime、runtime-compose、runtime-ktx、viewmodel、
    viewmodel-ktx、viewmodel-savedstate):2.8.3
  - `core:core`、`core-ktx`:1.13.1;`annotation:annotation`:1.8.1;`annotation-experimental`:1.4.1;
    `arch.core:core-common`、`core-runtime`:2.2.0;`collection:collection`、`collection-ktx`:1.4.4;
    `savedstate:savedstate`、`savedstate-ktx`:1.2.1;`emoji2:emoji2`:1.3.0;`profileinstaller`:1.3.1;
    `startup:startup-runtime`:1.1.1;`concurrent:concurrent-futures`:1.1.0;`autofill`:1.0.0;
`customview:customview-poolingcontainer`:1.0.0;`graphics:graphics-path`:1.0.1;`interpolator`:1.0.0;
`tracing`:1.0.0;`versionedparcelable`:1.1.1;`webkit:webkit`:1.12.1(built-in browser)
- Usage: Interface and system component library, entered into APK.

## PRoot

- Project: PRoot
- Copyright: Copyright (C) 2015 STMicroelectronics (the author list can be found in the source code
  package `AUTHORS`)
- License: GPL-2.0-or-later, see `licenses/GPL-2.0.txt` for the full text
- Source: https://github.com/proot-me/proot/archive/refs/tags/v5.4.0.tar.gz
- Version: 5.4.0
- SHA-256 (source archive): 29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68
- Usage: Standalone executable program. Compiled by `tools/sandbox/build-native.sh` from the above
  source code to `app/src/main/jniLibs/arm64-v8a/libproot.so` and its loader `libproot_loader.so`,
  it is released with the APK; Mina only runs it as a child process and does not link it. The source
  code address, version and check value are also recorded in `tools/sandbox/sources.lock`.
  `libproot.so` does not embed a loader (only embeds empty placeholders), and `libproot_loader.so`
  is used by `PROOT_LOADER` during runtime.
- Modifications: Four patches have been applied, also released under GPL-2.0-or-later:
  `tools/sandbox/patches/proot-0001-exitkill.patch` (the tracking option adds `PTRACE_O_EXITKILL`:
  the kernel kills all tracked processes when PRoot exits);
  `tools/sandbox/patches/proot-0002-seccomp-trap-enosys.patch` (system calls blocked by SIGSYS by
  the seccomp filter other than PRoot are changed to return `-ENOSYS` and cancel the signal; only on
  arm64 with faccessat2 with the `AT_EACCESS` flag is changed to faccessat);
  `tools/sandbox/patches/proot-0003-arm64-new-path-syscalls.patch` (the arm64 system call number
  table is supplemented with statx and the new path call is added: faccessat2, fchmodat2 for path
  conversion, execveat, openat2, io_uring_setup, the new mounting interface and *xattrat/file_*attr
  return `-ENOSYS`; And correct the out-of-bounds judgment of the call number lookup table);
  `tools/sandbox/patches/proot-0004-link2symlink-fstat-nonfile.patch` (link2symlink extension no
  longer reads the uninitialized stat structure when fstat is used for non-file descriptors such as
  pipes and sockets, and changes successful calls to failures).
- The accompanying component uthash (BSD license, Copyright (c) Troy D. Hanson): The PRoot source
  tree refers to it in the form of a submodule, and is only used by the `care` extension; the
  directory in the source archive is empty, and Mina's compilation does not include `care`, so there
  is no uthash code in `libproot.so`.

## talloc

- Project: talloc (Samba)
- Copyright: Copyright (C) Andrew Tridgell 2004;Copyright (C) Stefan Metzmacher 2006
- License: LGPL-3.0-or-later, see `licenses/LGPL-3.0.txt` for the full text; LGPL-3.0 is an
  additional clause on top of GPL-3.0, see `licenses/GPL-3.0.txt` for the full text of GPL-3.0
- Source: https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
- Version: 2.4.3
- SHA-256 (source archive): dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
- Usage: Only compile `talloc.c` (unmodified) and statically link it into the independent executable
  program `libproot.so`; Mina itself does not link it. See `tools/sandbox/` for all the source code
  and compilation scripts required for relinking.

## Alpine Linux

- Project: https://alpinelinux.org/
- Version: 3.22.7 (aarch64 minimal root file system), plus Python 3.12, Node 22, Pillow, pypdf and
  their dependencies
- License: Each software package has its own license (GPL, LGPL, MIT, BSD, MPL, etc.); see
  `app/src/main/assets/runtime/packages.json` for the name, version and license of each software
  package, and the "Open Source License" page of the App is also listed one by one.
- Usage: **Released with APK**, file `app/src/main/assets/runtime/rootfs.tar.xz`, generated by
  `tools/sandbox/build-runtime.py` pressing `tools/sandbox/runtime/sources.lock` from Alpine
  official source and verifying the signature; Mina only runs the programs in it as a sub-process
  and does not link them. The corresponding source code of the GPL/LGPL/MPL software package
(Alpine's build recipe and upstream source code) is put into `mina-<version>-gpl-sources.tar.gz` with
each version. See `tools/sandbox/README.md` for the method.

## Guava ListenableFuture (pulled in by AndroidX)

- Project: https://github.com/google/guava
- Artifact: `com.google.guava:listenablefuture:1.0` — Apache-2.0 (based on Maven POM)
- Usage: Used as a library dependency, its code has not been modified.

## XZ for Java

- Project: https://tukaani.org/xz/java.html
- Version: 1.10(org.tukaani:xz)
- License: 0BSD(BSD Zero Clause License);Copyright (C) The XZ for Java authors and contributors
- Usage: Unzip the built-in operating environment when starting for the first time.

## commonmark-java

- Project: https://github.com/commonmark/commonmark-java
- Artifacts:
`org.commonmark:commonmark:0.30.1`、`commonmark-ext-gfm-tables:0.30.1`、`commonmark-ext-gfm-strikethrough:0.30.1`
- License: BSD-2-Clause
- Usage: Depends in the form of a library without modifying its code; parses the Markdown answers in
  the conversation into a syntax tree, which is then displayed by Mina's own interface code
  (`app/src/main/java/com/meenai/mina/ui/MarkdownModel.kt`).
- License text:

```
Copyright (c) 2015, Atlassian Pty Ltd
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```


