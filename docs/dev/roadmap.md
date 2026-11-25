# Mina road map

Principle: **Get things done first, then seek speed, and then expand capabilities**. The repair
mechanism is categorized by root cause and does not patch individual cases. The current structure is
[`architecture.md`](architecture.md).

## Completed

- **Self-Hosted Remote CLI/MCP**: Single-user, single-device HTTPS gateway with mobile outbound WSS,
  one-time pairing, mobile unified token authentication, transparent forwarding and disconnection
  semantics; reuse existing tasks and device capabilities. See [remote-mcp.md](remote-mcp.md) for
  deployment. The compatibility of public network certificates with specific clients still requires
  actual deployment and acceptance.

- **Things about the background virtual screen app**: Built-in mobile phone assistant (after
  wireless debugging, paired once), virtual screen, barrier-free screen reading and operation, text
  recognition supplementary reading, takeover, mobile phone composite tools (query, continuous
  command, cross-screen collection, search, extraction, pop-up window, wait, event, gesture, system
  command).
- **Built-in browser**: WebView on private virtual display, real input via DevTools protocol;
  desktop identity, tab pool, login status and "website login" settings, web page takeover, download
  and upload, read-only scripts.
- **Linux Sandbox**: The Alpine operating environment (arm64) that comes with the APK, a workspace
  for each session, network disconnection by default, file entry and exit, knowledge and skills
  built-in scripts, and mobile phone tools in the sandbox.
- **Runtime**: Run and inbox (interruption during running), task center (one run per conversation,
  queuing, parallelization of different conversations), delivery ledger, marking interruptions and
  notifying them after restart, unified resource coordination, assembly by capabilities, subtasks
  (read-only research).
- **Main loop**: The only execution entrance, tool registry and service by attributes, observation
  as the center, answer without adjusting the tool, `end_not_done`, system generated source,
  determine the outcome in the background after answering, limited retry for temporary model errors;
  the tool engine does not directly rely on the interface state (`PackageDependencyTest` holds).
- **Context**: Cache partition, history only append, one-time compression when almost full, user's
  original words will never be cropped, and will be filled in after compression.
- **Timing and condition trigger**: one-time / fixed interval / local time every day and week, fixed
  reminder, read-only condition check and status change deduplication, do it again when idle,
  accurate alarm clock capability test, restart and rearrangement after time zone change, result
  return to original dialogue, plan page.
- **Memory and Learning**: Facts, preferences, practices (practices subject to approval), user
  portraits, encrypted storage; drafting - acceptance - trial - verification process of personal
  skills and App knowledge drafts.
- **Knowledge**: Built-in App, website knowledge and skills (under `core/src/main/resources/`, see
  the generated index and README for the list), unified format, two pairs of search/reading tools.
- **Token Usage**: Each model call is accounted at the source (service provider, model, purpose),
  and the machine stores it hourly for 400 days; a page in the settings shows the total usage of 24
  hours / 7 / 30 / 90 days / customized, the trend stacked by service provider, and the ranking of
  service providers and models.
- **Remote screen opening and unlocking**: `mina_device_state` / `mina_wake_device` /
  `mina_unlock_device` connected to the computer (MCP), off by default, turned on on the phone and
  save the PIN or password; the credentials are encrypted and stored locally, and are only directed
  to the system lock screen interface. If it fails once, it will be paused, and it will be resaved
  on the phone before trying again (see architecture §6).
- **Evaluation**: Real machine evaluation questions under `eval/tasks/`, divided into tuning pool
  and retention pool; fake model script and prompt word golden sample test.

## Next

1. **Running real tasks on real machines**: Failures are classified according to root causes,
   repaired at the mechanism level, and similar tasks are improved together.
2. **Re-open verification and approval** (see "Known Issues" No. 1 below).
3. **Mobile Capabilities v2**:
   - Multiple steps at a time can have waiting conditions (wait for text to appear, wait for the
     page to be quiet before taking the next step), reducing model round-trips;
   - Streamlined observation: the screen reading result only displays the differences from the
     previous screen and a shorter interface description;
   - Tools are provided on demand: only a filtered tool directory is provided at the start, and the
     complete definition is loaded when needed. As the number of tools increases, the fixed prefix
     will not be expanded;
   - Based on the same model and the same task, the first feedback time, number of model
     round-trips, screen reading and action time, completion rate, and missed reading and
     duplication of chat collection were measured.
4. **Subsequent stages of scheduled tasks**: condition monitoring in the logged-in App; multi-step
   monitoring and orchestration; upper limit of running time and timeout cleanup; do-not-disturb
   period; continuous failure merge reminder; condition results with observed values, "reminder only
   when the value changes significantly" and cooling; previewing the next few times when creating
   and editing; making plan suggestions based on the long-term intentions clearly stated by the user
   (will only take effect after the user adopts them).
5. **More sources and capabilities**: Channels (WeChat, etc.; channel interface, owner identity,
   question number correspondence have been defined in `core/run/`), transport layer and setting
   entrance of MCP server and client.

## Known issues and suspensions

1. **Final verification is turned off during the development phase** (User decision: The development
   phase remains closed). `TaskRun` in `reviewFinalAnswer = false`:
   - The answers will all be recorded as "unverified"; the learning will look at the outcome
     judgment after handing in the answer (`LearnGate`, see architecture §3 "Result Judgment"), and
     will no longer look at the verification, so skill drafts, App knowledge, experience routes, and
     trial transitions will occur as usual; after verification is opened, those who have passed but
     failed will still be blocked;
   - Risk approval is based on global inquiry/autonomy (see architecture §4): when asking, only the
     step of spending money must be agreed; when autonomy is turned on, payment is not blocked, and
     the early return of `autonomous` (starting with `ConfirmDecision.judge`) is ranked before the
     "Don't do it yourself with Mina" (`ownApp`) check, so this layer of protection does not take
     effect in autonomous mode (user decision: not changed for now. Agent operates Mina Your own
     interface can also be used to help users do things and can be decided later);
2. **Insufficient protection for crash recovery**: The aftermath of the interrupted run (replying
   the results, notifying the user, recording a round of "task interruption" in the dialogue) is
   done by `RunRecovery` (`app/task/RunRecovery.kt`): after the process is killed, the user will
   receive "Interrupted" as soon as he opens the App (`MinaApp.onCreate`), and when the task service
   is destroyed but the process is still there (`TaskService.onDestroy`), it will be notified on the
   spot; whether it has been notified, check the delivery ledger, each interrupted run is only
   notified once. There is still no protection of "suspension of ancillary services if continuous
   abnormal restarts occur". Do not continue running from the middle (the screen state cannot be
   restored), only "interrupt → notify → user restart".
3. **Web page read-only script to-do** (`core/cap/web/ReadScript.kt`, proposed for review, not done
   yet):
   - The judgment of "whether it is a secret input box" only depends on the changes after the
     read-only environment is established. The password box that was changed to text before that
     cannot be recognized. Consider sharing the "used to be password box" markup with the page
     script.
   - "It used to be a secret input box" only focuses on the changes in the main document. The
     password box that was changed to text in the same source iframe and shadow root cannot be
     recognized; the autocomplete `<select>` marked with the bank card can read the selected value;
     when the value exceeds the size of the box, scrollWidth and scrollHeight can see the length or
     number of lines.
   - The word of the secret textarea: isEqualNode can compare whether it or its text node is the
     same as the existing node on the page; after the page assigns a value to the output containing
     the secret textarea, the defaultValue retains the word before the assignment; the page moves
     the text node held by the model into another secret textarea (or puts the value Move the
     attribute node to the password box), write the secret and then remove it. When the model does
     not read it in the new box, it is still calculated according to the original box and can be
     read.
4. **Small gap in triggers**: After changing the time zone, plans that are repeated according to
   local time will not be recalculated according to the new time zone until the next claim;
   `LocalScheduleAdmin` lists all plans, and the ownership is not divided according to dialogue.
5. **Clipboard**: The webpage editing area relies on pasting input. Starting from Android 12, the
   home screen will briefly prompt "Accessing the clipboard"; sensitive text such as verification
   codes and ID numbers will stay in the clipboard for about one second when pasted. To completely
   avoid the need for Mina to be used as the input method to submit text, major changes will be
   postponed.
6. **Web page tasks do not enter experience playback**: The difference between the dynamic content
   of the web page and the login state will cause frequent failures in matchups. Please stabilize
   the playback on your mobile phone first.
7. **Skill scripts that require third-party account authorization** (cloud documents, map
   interfaces, etc.) will not be done for the time being; doing so requires an entrance to securely
   store service credentials.
8. **Memory does not do cloud synchronization and export**; if you want to do it in the future, you
   need to design an encrypted export separately.
9. **Real machine to be verified**: Various system operations of the built-in assistant, automatic
   recovery after the assistant is killed or wireless debugging is turned off, automatic recovery
   after restarting, booting with authorization failure after a long period of non-use, web page
   rendering process crash, accurate alarm clock authorization and deactivation on Android 13–15,
   and scheduled tasks during the lock screen. Currently, it is mainly covered by single testing and
   device testing, and it also needs to be run on a real machine one by one.
10. **Irreversible questions for evaluation**: Do not run by default. To run, you must pass `-e
    evalAllowIrreversible true`; use a new dialogue for each question. Sending and deleting
    questions will actually be completed (only the marked data and test account of the question
    itself will be touched); placing an order in query mode will stop on the confirmation card (the
    automatic answer of `EvalHooks` can be picked up). There is no such safety net when placing an
    order when autonomy is turned on in the developer options, and the order will actually be
    placed.
11. **Later**: Connect to a computer and dim the screen during tasks to save power.
12. **Suspension capsule step by step**: the first step (floating layer host, switch, working /
    needing you / you are processing / completed capsule, taking over and returning, and cooperating
    with notifications), the second step (clicked card: small screen, step, stop, take over, agree /
    Answers, results, lists of multiple things; drag the borders, shrink into one, remember the
    location), the third step (input in the card: interrupt, answer, continue chatting, continue the
    latest conversation or start a new conversation when free; get out of the way when the input
    method pops up), the fourth step ( Multiple things: Sort the list by priority, switch between
    cards, press the actual takeover queue one after another, the next item will immediately become
    "Need You" after handing it over), the fifth step (feel: it will fade away after a few seconds
    if you don't touch it, the status point will remain solid when it shrinks into one, and others
    When the input method pops up in the App, it should be shrunk into a small piece and moved to
    the top, and then moved back proportionally after the screen is turned). It has been done. After
    using the real machine to check each item, whether it should be automatically shrunk when
    playing in full screen, and whether the words on the capsule should be added with short
    sentences.

