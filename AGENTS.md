# Harness Agent: rules for every agent

Project
- App name: Harness Agent. applicationId com.danish.harnessagent. Kotlin package com.meenai.mina stays (do not rename).
- Keep unchanged for compatibility: keystore aliases mina-provider-keys and mina-speech-keys, Author.Mina, From.Mina, /opt/mina/skills, trace keys mina.*, Theme.Mina, string IDs, relay mina-relay.v2, header x-mina-client, cookie mina_language.
- Accessibility component: com.danish.harnessagent/com.meenai.mina.a11y.HarnessAccessibilityService.
- User-visible text in English or Hindi, never Chinese.

Environment
- Termux on Android. No Android SDK here.
- Do not run Gradle locally (not even :core:test) unless the task asks. Push and let GitHub Actions test it; CI is faster than this phone.
- Read only files the task names, plus what grep finds. Do not re-read the whole repo.
- Keep thinking short on simple edits. Go straight to the change.

Git
- Work on the branch named in the task. Never commit to main unless the task says merge.
- git add by file name only, never -A or a dot. Never commit .jks, .apk, .idsig, build/, .gradle/.
- Never touch LICENSE or NOTICE.
- Ask before deleting any file.
- Only one chat changes this repo at a time.

CI loop (do this yourself, do not ask)
- After push, watch the CI run with gh run watch.
- If CI fails, read the failing tests, fix, push again. Up to 2 fix attempts.
- After 2 failed attempts, stop and report the failing test names and your best guess.
- Never fix a test by deleting it or weakening its assertion.

Report (short, in Hinglish)
- What was built, in 3-5 lines.
- Commit hashes, git status --short, CI run ID, result per job.
