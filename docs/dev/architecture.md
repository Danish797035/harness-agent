# Mina architecture

This document describes the overall structure of Mina according to the current status of the code,
making it easier to find the code. What has not been done yet and what is half-finished is written
in[`roadmap.md`](roadmap.md)。

Path abbreviation: `core/…` = `core/src/main/kotlin/com/meenai/mina/core/`, `app/…` =
`app/src/main/java/com/meenai/mina/`.

## 1. Modules and layering

The repository has two Gradle modules:

- `:core`: Pure Kotlin (JVM), not dependent on Android. The main loop, tools, platform-independent
  parts of capabilities, context, verification, memory, knowledge, triggers, and run records are all
  here, and can be run on a computer with single tests and fake models. Prompt words, built-in
  knowledge and skills are its resources (`core/src/main/resources/`).
- `:app`: Android host. Interface, front-end service, virtual screen, accessibility, built-in mobile
  assistant, WebView, sandbox, file storage, and Android implementation of each interface in
  `:core`.

There is also an optional independent Go gateway `tools/relay/`, which is used for single-user,
single-device public network remote CLI/MCP. It does not enter the APK, the phone accesses via
outbound WSS; see [remote-mcp.md](remote-mcp.md) for deployment and phone authentication.

```
 Sources   Chat UI │ Scheduled/conditional triggers │ Parent task (subtask) │ Computer (CLI / MCP)
          └──────────┬──────────┘
          TaskService (foreground service, Android host)
          TaskCenter (one Run per conversation, concurrency cap, queue) · RunStore · Outbox
                     │
          TaskRun ── one run: install capabilities, build AgentLoop, wrap up
                     │
          AgentLoop (scheduling only) ← RunInbox (steer, answer, confirm, hand back, cancel)
            ModelCaller · ContextBuilder/ContextCompressor · TurnPolicy/StopRules
            ToolDispatcher → ActionExecutor (the only execution entry point)
                     │  CapabilityProvider (the core does not know concrete capabilities)
          phone      web       sandbox      (mcp client: skeleton)
```

Dependency direction between packages in `:core` (`PackageDependencyTest` check):

- Capabilities (`core/cap/<capability>/`) depend on the core. The core does not reference any capabilities,
  nor do capabilities reference each other; only the assembly layer (`core/assembly/`) recognizes
  several capabilities at the same time. The directories of each capability under `cap/` in the App
  also do not reference each other.
- The tool engine (`core/tool/`: `ToolDispatcher`, `ToolContext`, `AgentTool`, etc.) does not
  reference the interface state (`core/uistate/`). The engine only uses the interface layer through
  `SurfaceLayer` (`core/tool/SurfaceLayer.kt`), and the interface only contains the screen ID, tool,
  parameters and results; the implementation is `Interfaces`, which is built by the assembly layer
  and put into the tool context. The interface tool reads the screen status from the context through
  the extension in `core/uistate/ScreenContext.kt`.

## 2. Tasks and operations

### Run

A run is described by `RunSpec` (`core/run/RunContracts.kt`): `id`, `goal`, `threadId`
(conversation), `priorTurns`, `provenance`, capability subset `capabilities`, `readOnly`, `parent`,
`stepBudget`.

- `RunProvenance` (`core/run/RunProvenance.kt`) Note where this operation comes from and whether the
  user is present: `RunOrigin` = Ui / Channel / Trigger / Mcp / Child; `Attendance` = Present /
  Reachable / Unattended.
- Running phases `RunPhase`: Queued, Preparing, Running, WaitingUser, Parked, Paused, Finishing,
  Aftercare, Ended, Interrupted.
- "Who initiates this step" is another matter, see `Origin` in §4.

### mission center

`TaskCenter` (`app/task/TaskRegistry.kt`, held by `TaskService`) manages the start, queue and end of
the operation:

- There is only one Run per conversation at a time. In the same conversation, if the user sends
  another message while the task is in progress, it will not open another task, but will be given as
  an interruption to the running Run (`TaskService.begin`).
- Tasks in different conversations can be run in parallel, and the upper limit is shown in §8; the
  first-in-first-out queue that exceeds the upper limit will be added in sequence when there is a
  Run at the end (skip the ones that already have a Run in the conversation). When the parallel
  switch is turned off (default), conversations cannot be switched on the task-in-progress interface
  (`ChatViewModel.canSwitch`, other conversations in the sidebar `ChatDrawer` cannot be clicked and
  there will be a prompt). Tasks assigned to another conversation in the service (conversation page,
  suspension capsule, browser page, timing, computer) will all be queued and will not be lost
  (`TaskService.begin`). The conversation page waits for the service to be free before handing it in
  (at this time, the message says "Wait until the previous task is completed before starting",
  `ChatViewModel.begin`). This prevents the new sentence from being treated as an interruption when
  the previous task in the same conversation is still being completed; between the completion of the
  search and the handover, a task starts somewhere else, and it is still queued, and the task starts
  after the completion of that task. At this point, stop the task that only stops this conversation
  (`TaskActions.cancelIn`), and does not touch the running one.
- When the scheduled task is up, if there is a task running in the original conversation, queue up
  in this conversation and wait for it to end (`queueInConversation`). It is not regarded as an
  interruption and is not lost.
- The running status is written into `runs-v3.json` (`app/task/FileRunStateStore.kt`, atomic
  replacement writing).
- `app/task/TaskCenterState.kt` is just the status to be displayed on the interface (running list,
  parallel upper limit, interrupt status, etc.).

### Inbox and interrupts

During operation, everything coming in from the outside goes through `RunInbox`
(`core/run/RunInbox.kt`), and the types are `RunInput`: Steer (interjection), Redirect, Answer,
Confirm, HandBack, and Cancel.

- **Interjection is a supplement**: Everything that has been done will be retained. With each
  comment, the model request (`AgentLoop.routedOrRedirect`) that is still waiting is removed, and
  the query is repeated after the existing history. The message sent is not changed word by word,
  and the request prefix is only increased. The tool calls that have entered the execution
  entrance are completed as usual, the results are recorded, and the next time you ask the model,
  add an interrupt (`AgentLoop.appendSteers`). Steer and Redirect go the same way.
- **The words already displayed during the interruption are left behind**: If the withdrawn request
  has already displayed a paragraph of words (`TaskState.Running.preview` on the interface),
  `AgentLoop.keepInterrupted` will record this word in the history as a reply with only text, and
  connect it before the interruption. What the model sees when asking again is the same as what the
  user sees; the thinking content and the half-written tool call are not displayed or recorded. No
  words are displayed (streaming display is not turned on, no words have been printed, and the data
  is cleared during retry) or nothing is recorded when there are no interruptions in this batch. The
  real-time text on the interface will be cleared on the spot, and the late words from the network
  thread will no longer be displayed after removal. This text also sends a running event
  `reply_interrupted` (after truncation and desensitization), `TaskRun` and hooks it to the
  interruption immediately handed over to the model (`SteerNote.interrupted`). The conversation page
  is displayed in light color above the interruption, with a line of small text below "Interrupted
  by your new message"; the conversation is saved with this round (`Turn.interruptedReplies`, and
  `steers` One-to-one correspondence, the uninterrupted one is an empty string), it will still be
  displayed when the conversation is reopened. It only gives the interface, not the context of the
  model in later rounds.
- **Replies that have been finished before interrupting are left**: Replies without calls have been
  displayed and recorded in history, and the interrupt is retrieved at this time (the reply happened
  to be finished first, the interrupt arrived later, or the interrupt arrived when sorting out the
  source before handing over), `AgentLoop.keepFinished` sends the paragraph of words on the
  interface as a running event `reply_finished_before_steer` (after desensitization, no truncation:
  the interface needs to display the full text), clears the real-time text on the spot, and then
  connects the interrupt. `TaskRun` is also linked to the first interruption of this batch
  (`SteerNote.finished`). The dialogue page is displayed above the interruption as the answer
  (without the mark "interruption"), and is saved with this round (`Turn.finishedReplies`,
  corresponding to `steers`); nothing is linked when no words are displayed. Interruptions that have
  not yet been handed over to the model on the dialogue page are listed behind the growing answer
  (`steersAroundDraft`): that answer was written before the interjection arrived. It will stay in
  place when it is finished or interrupted, and will not jump from below the interjection to the
  top.
- **When does the interjection enter the context**: `AgentLoop.appendSteers` Get the inbox before
  each step of adjusting the model; take a look at the model when it gives an answer without tools,
  and continue with the new interjection. Before handing over the answer (after checking and sorting
  out the sources), use `RunInbox.closeUnlessSteered` to read and close it in the same lock: if
  there is still something to say, don’t finish it, just take it and continue working on it, only
  close the inbox if there is no more, and the next thing will become the next thing. When the model
  adjusts end_not_done by itself, the newly arrived interrupt will also be allowed to continue; if
  the guard calls a stop or an error occurs, it will still end.
- **Waiting ends early**: The inbox gives a signal (`core/run/SteerSignal.kt`) that "there is an
  interruption but has not yet been handed to the model", which is installed in the entire running
  coroutine context. Calls that don't lose anything are wrapped by `waitOrSteer`, and end early as
  soon as the interruption arrives: pure waiting and tools that only ask to see the graph model
  (declaration `AgentTool.yieldsToSteer`: wait, phone_wait, phone_event, web_wait_for, describe,
  find_visually, unified packaged by the distributor), equivalent words in `phone_run`, `do_steps`
  Each section of the interface according to conditions, the pause before page change (`PageDwell`),
  and the interval between two external actions on the same website or app (`ActionExecutor`). The
  truncated call reports `skipped_for_new_message`, which does nothing this time; in `do_steps` this
  step is skipped along with the following substeps. After the action is completed, wait for the
  interface to be quiet and do not cut off when judging the effect (it gives the result of the
  action). The retry wait of the model call is already in the model request and is removed together
  with it.
- **Rhythm protection is not lost**: When the dwell and interval are cut off, the action itself is
  not performed (the tool results will explain why, and adjust it again if necessary). Both are
  calculated based on time (the interval is calculated from the completion of the last external
  action, and the stay is calculated from the time from this page). The part that has not been
  stopped is not counted as stopped. The remaining time will still be made up before the next page
  change or external action; interrupting only allows the model to think now, and will not make the
  next action come faster.
- **Multiple steps at a time**: `do_steps` If there is an interruption between two steps, it will
  stop there. The result indicates "skipping to process new messages" (`skipped_for_new_message`),
  which is not considered a failure (`core/agent/BatchRunner.kt`). After completing a certain step,
  there is an interruption while waiting for the interface according to the conditions, and the
  waiting stops immediately (`wait.stopped` in that step is `new_message`), and the following
  sub-steps are still skipped.
- **There will be an echo immediately on the interface**: `TaskRun.steer`. If it is successfully
  sent to the inbox, mark this sentence as "Received, next step" (`SteerState.Received`). When it is
  handed over to the model, it will be changed to "Added to the current task"; both the dialogue
  page and the floating capsule's dialogue card will have this line of small words (`steerLabel`)
  written under this sentence.
- **Withdraw and edit interrupt**: By default, it is still directly inserted into the current task.
  Long press on the message that has not been handed over to the model on the dialogue page can
  withdraw or retrieve the edit; `RunInbox.withdrawSteer`, `takeSteers`, and closing share the same
  lock, and will return failure when it has been handed over to the model or has been closed. After
  success, it will be marked as `SteerState.Withdrawn`. The conversation page and capsule will no
  longer be displayed, and the added interruption will not be counted. Withdrawal from
  `ChatViewModel.held` when the task has not been handed over to the service. Editing restores the
  text and quotes. Editing is not provided when the input box already has drafts, quotes or
  attachments; editing is not provided when the floating capsule is inserted with the screen or the
  browser page with the page description (`editableSteer`): When retrieved, the input box begins
  with a screen mark or a few lines of the original page description. If you delete it, you will
  lose the screen and page, and if you modify it, you will not be able to recognize the page.
  Interruptions added by the computer (`SteerNote.remote`) are also displayed in the conversation,
  but withdrawal and editing are not provided, and the inbox refuses to withdraw (the editor will
  turn the words that are only for the current task into mobile phone interruptions, and may be
  transferred to the next thing; whether to withdraw or not is decided by the computer);
  `remoteSteers` reports `Withdrawn` by pressing `not_applied`, and there is no external status.
- **Interruptions that have not been processed in time**: Interruptions that have not been consumed
  at the end of the run. If the user does not press stop, they will become the next task of this
  conversation; if the user presses stop, they will be discarded (`TaskRun.handoffUnconsumed`(As
  long as it is not consumed at the end). The interjection arrives after the run has been closed,
  by`TaskRun.steer`When it cannot be received in the inbox`deferMessage`,or`TaskService.begin`Queue
  the next task when you find that the task has been completed.
- **Stop**: Cancel is a control signal and is not queued after interrupting. `TaskRun.cancel` Cancel
  subtasks at the same time; end the takeover while it is in progress; and remove the queued tasks
  directly from the queue.
- **Explanation after completion**: The interface shows that it has stopped on the spot, and the
  input box is empty; then add a few lines of explanation as the answer for this round (what has
  been done, what has been seen, how many batches of things have been sent and which ones have been
  completed, and which ones have not been completed). The ending is still canceled (evaluation,
  result classification, and learning are all counted as cancelled). `TaskService` When finishing,
  first register the run number into `TaskCenterState.summarizing` (the interface will write
  "organizing what has been done..." under "Stopped" accordingly), write down the "cancelled" round,
  and then write it with `TaskRun.stopSummary` in the process-level scope. Use
  `ConversationStore.updateTurn` to improve the round after writing it. Remove the registration if
  it is written well or cannot be written; do not block the ending, next thing and click stop again.
  The writing method is `StopSummary` (`core/agent/StopSummary.kt`): only read the running record of
  this time (activity, intention of that step, the last two paragraphs read, half of the words
  written when stopping, upper limit, no screen), please model once (lightweight model is given
  priority, if there is an error, change to the main model, no tools are given, a total of 12
  seconds), if there is no model, error, or timeout, just press the record to spell directly
  (`StopSummary.plain`). External actions (send, submit) are numbered according to `outward`
  (`ActionExecutor` is marked according to irreversibility judgment) on the activity. He didn't do
  anything, didn't write any words, he just said he had stopped. The text of the next round is
  written as "You stopped this mission midway, before stopping:..." (`Conversation.context`),
  "Continue" to know where to continue. The original texts read in previous rounds are not included
  in the above text. You have to use the time model to check them yourself (`history_read`, see
  "Records of Previous Rounds").

### service

Results, questions, and notifications are sent via `Outbox` (`core/run/Outbox.kt`): write down what
you want to send first, and then send it. Status Pending → Attempting → Delivered / Failed /
Uncertain; the same delivery number is only delivered once; if the result is unknown after being
sent halfway, it is marked as Uncertain and will not be automatically resent. Recorded in
`outbox-v3.json`(`app/task/FileOutboxStore.kt`).

Currently the only channel is `LocalChannel` (`app/task/LocalChannel.kt`): native interface plus
system notification. Channel interfaces `Channel` and `ChannelGateway` (owner identity, question
number correspondence, payment confirmation can only be done on the channel that can be confirmed,
`Channel.confirms`) have been defined in `core/run/`, and there are no other channels connected.

### After the process restarts

- `RunRecovery` (`app/task/RunRecovery.kt`) is a single entry point for operation aftermath, which
  is targeted at operations that were "interrupted and the user has not been notified". Two
  opportunities: after the process restarts, `MinaApp.onCreate` is adjusted together with `recover`
  (`TaskService.onCreate` is also adjusted, but only the first time in the process takes effect, and
  the later ones do nothing); when the task service is destroyed and the process is still there,
  `TaskService.onDestroy` is interrupted and `settleInterrupted` is adjusted on the spot without
  waiting for the next startup (use the App for aftermath) level scope, the service will be
  completed even if it is gone; the scan at startup will be picked up and killed on the spot before
  being completed). Marking is completed synchronously in the call (`RunStore.interruptUnfinished()`
  marks the unfinished run as Interrupted, and the running run at the moment is excluded through
  `live`), and the aftermath runs in the background: a reply that was saved but not delivered is
  delivered once more (marked as recovered); if the delivery status is unknown it is not resent, and
  only "the last result may have been delivered" is shown; if there is no reply, a system
  notification "the run was interrupted, you can start it again in the conversation" is sent, and a
  failed round "the task was interrupted, it can be restarted" is recorded in the conversation (the
  same set as other failure results, shown as an unfinished round in the UI). **"Already told" has
  no separate field; the delivery ledger decides**: if any of `interrupted:<run>`,
  `uncertain:<delivery>` or `failed:<delivery>` exists, the run is not processed again; a run whose
  reply was delivered becomes Ended. So repeated calls and both timings notify only once. **Never
  resume from the middle**: the screen state cannot be restored.
- `TaskRecovery` (`app/task/TaskRecovery.kt`, run when the App starts) restores the system state
  rather than running: the App that was muted during the task restores the sound, and the clipboard
  borrowed by the task is returned to the user.
- When loading the subtask record, change Running to Unknown and do not rerun
  (`app/task/FileChildRunStore.kt`).
- For the processing of scheduled task windows, see §10.

## 3. Main loop

`AgentLoop` (`core/agent/AgentLoop.kt`) only does scheduling, and the specific judgments are in one
class:

| kind | do what |
|---|---|
| `ModelCaller` | Group request, model adjustment, streaming output; compression and recovery, temporary error retry, context overlong retry, large and small model offloading |
| `RetryPolicy` | 429, 5xx, limited retries when disconnected (up to 3 times, exponential backoff, total wait for each task has an upper limit) |
| `ToolDispatcher` | Find tools by name, hold evidence ledgers, and spell out clues for each ability. |
| `TurnPolicy` | Progress check (only when the number of steps is limited), a reminder when the reply is empty or truncated, a return when the tool call is missed as text, a reminder when it is time to follow up, a return when the answer is in a different language, and only `end_not_done` in the last round (only a time when the number of steps is limited) |
| `StopRules` | When does it end: give up, guard stops (same action repeated, no progress, repeated readings, too many failures, sandbox spin), model failure, run out of steps (only when there is a limit on the number of steps) |
| `BatchRunner` | `do_steps`: give 1-20 known actions at a time, execute and check them one by one; a step may carry wait conditions (`expect` appears, `expect_gone` disappears, `expect_app` foreground app or page address, `wait_stable: true` the UI is stable, `timeout_s` defaults to 8 seconds and is at most 20 seconds). After acting, the code reads the screen and waits until the condition holds before the next step, instead of asking the model "still loading"; a dialog, a window of another package covering the screen, or an app switch stops it early. The result records `wait` (`met`, `stopped`, `waited_ms`). Waiting itself lives in `core/uistate/UntilWait.kt`, shared by phone and web, and wakes through the port's `awaitChange` (accessibility event / page change); wait time counts as waiting in the run summary, not as an operation |
| `IntentGate` | When the task initiated by the conversation interface requires a non-search tool for the first time, it will be judged whether the user is chatting, looking up information or doing errands; if it is judged as chatting, the user will not use the tool again to answer the question. When it cannot be judged (the call fails, or the reply is not one of the three words), the original decision of the main model is executed; it is only judged once per run (`AgentLoop.assessIntent`) |
| `StuckAdvice` | Add suggestions to the tool results when you get stuck; you can give up if you get stuck for the second time. |
| `NoticePolicy` | After viewing an interface, decide whether to supply the knowledge of the app/website corresponding to the model. |
| `GuideRecommender` | Index of the start: select up to 3 pieces of App/website knowledge according to the task text, and also list all skills (built-in and personal skills in use and trial). The full text is read by the model user `skill_read`. |
| `RouteReplay` | When "Do as you did last time" is turned on, the successfully matched route will be played back before adjusting the model for the first time (see §9) |
| `UserExchange` | Questioning, confirming, and taking over are three types of interactions that require user participation. |

There is no upper limit on the number of steps for normal tasks (`AgentLoop.UNLIMITED_STEPS` in
`TaskRun`), so the progress check, last round, and the number of steps used up are only used for
evaluation (`EvalHooks.maxSteps`) and subtasks (default 15 steps, the model can reach 40 steps,
`SpawnAgent`).

### Result judgment

- **No "Complete" tool**: The reply of the model without any tool call is the answer; the text
  accompanying the tool call is displayed as a process description. The words written down when
  interrupted by an interruption are not answers, but just let the model know what the user has seen
  (see "Inbox and Interruptions").
- **`end_not_done`** (`core/general/OutcomeTools.kt`): Use it to end when you can't do it or can
  only do part of it. `message` writes down what has been confirmed, where it is stuck, and what is
  on the interface now; `subtype` is one of `blocked`, `login_incomplete`, `captcha_incomplete`,
  `route_failed`, `device_unavailable`, and `interaction_failed`.
- **Before submitting the answer**: Empty reply or truncated reminder; some inference services
  occasionally do not parse the tool call of the model, and the call becomes the text as it is
  (`LeakedToolCall`: A common call tag followed by Mina’s tool name), this is not the answer: return
  it once and let the model be re-called through the tool interface, and it will be recorded in the
  running record.`core/agent/LeakedToolCall.kt`;Following another leak, it ended with a model error
  (`tool_call_as_text`), do not hand over the original mark to the user, and recalculate it after
  adjusting the tool normally; the user just answered something and the Agent did nothing, remind it
  once; the last action of changing the interface did not read back the result, remind it once. The
  text of the answer is obviously not in the language of the user’s most recent sentence
  (`StopRules.toolCallAsText`The number of letters to which letters belong, Chinese characters by
  word, the rest by word, the original text in quotation marks and the line with only the name are
  not counted; the user's sentence counts only the characters they typed this time, not the screen,
  the browser page description, the quoted passage, the attachment lines or the steer marker
  (`UserWords.typed`); no check is done when the start message carried only the screen, only
  selected text or only attachments, and a steer that carried only the screen or only selected text
  falls back to the previous sentence). The model is sent back once to rewrite the whole answer in
  the user's language; if it is still wrong after that, the answer is handed over anyway.
- **Source generated by the system**: `AnswerSources` (`core/verify/AnswerSources.kt`) selects
  observations (up to 6) that support the answer from the evidence ledger `EvidenceLedger` as the
  "source" below the answer; it is not written by the model itself.
- **Background result classification**: After the answer is delivered, `TaskRun.settleOutcome` uses
  `OutcomeJudge` (`core/agent/OutcomeJudge.kt`, giving priority to lightweight models, 30 seconds
  timeout) in the background to divide the run into Done / Partial / NotPossible / Unknown of
  `RunOutcome`. It does not change the answer, and the result is written into the running record and
  conversation record; the scheduled task will mark whether it failed this time accordingly.
  Learning and playback only recognize Done: there is only one judgment for admission,
  `LearnGate.done` (`core/agent/LearnGate.kt`): After handing in the answer, it is judged as Done,
  and there is no value that has not been matched after returning once; if you have run, you must
  verify the pass (if you have run, you will still be blocked, if you have not run, you will not be
  blocked). The drafting of personal skills and App knowledge, the conversion from trial to formal,
  the recording of experience routes and the count of successes are all asked by it (see §9, §11);
  whether to remember the route is judged by `LearnGate.shouldSaveRoute` (the route is open,
  completed, has a run number, and the target does not have a user screen).
- **Independent verification**: `Grounding` (whether the numbers, numbers, and citations in the
  answer are included in the content you have read, and whether the calculation is correct) and
  `Verifier` (independent verification model) are both in `core/verify/`, controlled by the
  `reviewFinalAnswer` switch. The product is currently closed (`TaskRun` in `reviewFinalAnswer =
  false`), so the answers are recorded as "unverified", see the roadmap "Known Issues"; the study is
  based on the ending judgment and is not affected by it.

## 4. The only execution entrance and tool

- **Only entrance**: All actions that change the external world go through
  `ActionExecutor`(`core/action/ActionExecutor.kt`). `ToolDispatcher.execute` requires a
  `ActionPass` (`core/tool/ActionPass.kt`). Its constructor is internal, and only `ActionExecutor`
  can create it. This avoids bypassing it from the compilation level; read-only calls to
  `executeRead`, which are not read-only, will be rejected. The execution sequence is fixed: guard →
  lock screen inspection → irreversible action inspection and consent → execution → check after
  action → record.
- **Interval between external actions** (`core/action/OutwardPacing.kt` pure logic, `ActionExecutor`
  according to it, etc.): Irreversible judgment recognition of sending and submitting (including
  buttons to contact others and submit applications, such as "Communicate Now", "Say Hello",
  "Submit", "Say Hi", "Easy Apply") are done continuously in the same website (according to the
  registrable domain name) or App (according to the package name), the first time does not wait, and
  there will be at least 8-20 seconds between the next and the last time. A random period of
  seconds. The more you do in the last 30 minutes, the upper and lower limits will add 0.5 / 1
  second each, up to 30 seconds; counting from the last time you finished it, the time spent reading
  the page and thinking about the next step is included, whether you change pages or not (the pause
  of page changes is controlled by the rhythm of the web page). The mobile phone and web page have
  the same judgment and the same set of intervals; when executing independently, the judgment still
  comes to recognize external actions (press the screen in the model's hand without reading another
  screen), but does not block it. The task can still be canceled after the judgment is made and
  before the action is started; the waiting time is recorded in the `waitMs` of this call and is not
  counted in the operation time. It is only installed by the App task (`AgentLoop(outwardPacing =
  …)`), and the core test is not equal by default.
- **Who initiated this step**: `Origin` (`core/action/Origin.kt`) = Model / Batch / Script / Replay
  / McpTool, each has its own strategy (whether the payment can be released with the user's consent,
  whether to stop when encountering a guard warning). Each action inside the compound tool
  (multi-step at a time, `mina phone` in the sandbox, web script) returns to the same entry through
  `SubActions` (`core/tool/SubActions.kt`), and the same execution and recording path as the
  single-step tool.
- **Tool declaration**: Each tool has a class, implements `AgentTool` (`core/tool/AgentTool.kt`),
  declares its own verbs, risks (Read/Write/External), capabilities, whether it can take multiple
  steps at a time, whether it can enter the experience route, concurrency mode, etc.; the code works
  according to these attributes and does not write down the tool name. `ToolRegistry` filters out
  available tools by task, and the tool definitions within a task are frozen unchanged.
- **Tools have two layers and expand only when used** (`core/tool/ToolGroups.kt`,
  `ToolDispatcher.offeredNow`): always present are the general tools (ask the user, end when
  nothing was done, wait, knowledge and skills, memory, schedules, subtasks, …) and the
  non-expandable capabilities (MCP); phone, web and sandbox are each a group (`Capability.folds`).
  At the start only the entry tools are given (`AgentTool.entry`), with the short description from
  `prompts/entries/<tool>.md`: calling `phone_launch_app`, `web_navigate` or `run_command` expands
  that group (`Entry.Opens`), while `phone_list_apps` and `web_read_page` (read the body of a URL)
  are enough on their own and do not expand (`Entry.Alone`). Takeover, reporting an obstacle,
  reviewing the screen and multi-step batches are given only after the group that has a screen is
  expanded (`AgentTool.withScreens`, counted as the `screens` group). After expansion, the full
  text of the whole group is given from the next request on, the entry switches back to its full
  text, and the capability description (`capabilities/<capability>.md`) is also spelled into the
  system prompt and kept for the rest of the task; a `moreTools` note in the result says so. If the
  model calls a closed tool from memory it still runs, and its group is expanded too (even with
  wrong arguments, so the next call can see the full text and fix them). Fixed order: always
  present → `screens` → phone → web → sandbox; expanding a group changes only the part starting at
  that group, and the request prefix is invalidated at most once by expansion. Pre-expanded at the
  start: groups really used in the previous round (`Turn.groups`, recorded by `AgentLoop.usedGroups`
  and stored in the session file; entries that are enough on their own do not count as used) and
  capabilities the current words certainly need (the sandbox when files from the workspace are
  attached, `Capability.preload`). Commands are not offered in read-only tasks; the sandbox group
  has no entry, so it is expanded from the start. Subtasks, scheduled triggers and other sources
  behave the same. The run record notes expansions as `tools_opened` and pre-expansions as
  `tools_preloaded`; `PromptVersion` is computed as if every group were expanded, so the same
  prompt version does not differ by which groups were expanded this time.
- **Approval**: `ApprovalPolicy` (`core/action/Approval.kt`) only asks for one action: the step of
  spending money (`Risk.payment`: irreversible judgment recognized payment, placing an order, or
  external, irreversible action hit when there is a payable amount with currency (¥, yuan, $) on the
  screen, see `ClassifiedRisk`; the characters in the traditional Chinese interface are first
  changed to simplified Chinese, judged according to the same set of vocabulary,
  `core/uistate/Hant.kt`). Do everything else directly: send, post, submit, delete, contact others,
  change account or security settings, type in the password box, look at the picture to find a point
  but can't see what it is, click on a target that can't be read, no questions asked. The only step
  to spend money is to initiate it on the mobile phone and ask when the user is present (`Ask`);
  operations initiated by channels, timings, MCP, subtasks and unattended operations cannot be asked
  and will be rejected (`approval_unavailable`). There is only one way to agree: only allow the next
  matching operation (`ConfirmationGate`: the same App, the same action, the object is on the
  screen, the declared amount is on the screen, within 3 steps, and no other writing operations are
  done in the middle). The payment itself (determined to be pay) is handed over to the user to pay
  by hand without consent (`hand_to_user`, reason=payment). The approval conclusions are recorded in
  the operation record (`RunEvent.Approval`, external, spent and not released), and the verification
  will be based on this to know that the task has done external things. There are no approval
  levels, and there is no within-task, long-term authorization.
- **Ask / Autonomous**: A global switch, in the "Ways of doing things" group at the front of
  Settings > Developer Options ("Autonomous Mode", test label `dev-autonomous`), the default is off
  to ask; the value is stored in the JSON of `ApprovalStore` (`autonomous`). `ApprovalControl`
  (`settings/ApprovalControl.kt`) will give a copy of `RunApproval` (whether it is independent or
  not) at the start of each task, and `TaskRun` will be installed accordingly: when asking
  `confirmations = ConfirmationGate()`, `autonomous = false`, the step of spending money will be
  blocked as `confirmation_required`, and the single category will be adjusted by the model.
  `request_confirmation` (The amount on the screen must be written. Applications that cannot write
  the amount will be returned directly and not handed to the user). The user answers on the card on
  the dialogue page, the card on the floating capsule, or the notification (only agree this time /
  disagree). The answer is handed over to the loop through `TaskService` → `TaskRun.confirm` →
  `RunInbox`; When `confirmations = null`, `autonomous = true`, and `request_confirmation` are not
  provided independently, the judgment will still be executed (the distance for external actions
  will still be recognized), but the payment will not be blocked. Change the switch
  (`ApprovalControl.setAutonomous`) and save it to manage subsequent tasks, and all running tasks
  will be changed accordingly: when starting the task, the task that is asking is changed to
  autonomous in the middle, and will not be blocked from the next step (the confirmation that has
  popped up will still wait for the answer as usual); when starting the task, the task that is
  independent is changed to asking in the middle, and it is left to the next task (the tool and
  prompt word remain unchanged within a task). Tasks, subtasks, and timed triggers that are
  initiated and chatted in the suspension capsule all press this switch; the exception is the tasks
  handed over by the computer via CLI/MCP: `ApprovalControl.start(remote = true)` will always press
  the query to start work and will not change with the switch. The payment step will never be done
  in this type of task (see the "Computer Connection" section); the subtask is inherently read-only.
  When asked, it will pass the level and spend money. When running unattended tasks such as
  scheduled tasks in the query mode, if a step that costs money is encountered, it will not pause
  and wait for someone: the execution entry is directly rejected (`approval_unavailable`). The model
  will tell the truth which step was not completed, and the other steps will be completed as usual,
  and the results will be notified as usual. `ConfirmationGate` does not exist in autonomous mode.
  The autonomous early return in `ConfirmDecision.judge` is ranked before the "Don't Mina do it
  yourself" (`ownApp`) check, so this layer of protection does not take effect in autonomous mode;
  Mina does not operate itself when asked.
- **Old tool name after renaming**: `LegacyNames` (`core/tool/LegacyNames.kt`) maps the old name to
  the current tool and is only used to read old routes, running records and personal skills; when
  the model calls the old name, it only prompts the new name and does not execute it for it.

## 5. Observation, action, place

- **Observation** is the only unit of core consumption: `Observation`
  (`core/observation/Observation.kt`) with type `ObsKind` (Read / Output / Reply / UserSaid /
  Attachment / Fact), source (capability id), place, title, text, screen, credibility, step count,
  time and number. Read it for evidence ledgers, sources, verifications, and operational records.
- **Untrusted content** (web pages, subtask results, etc.) are included in
  `<untrusted>…</untrusted>` (`core/observation/Untrusted.kt`) at one time when building
  observations.
- **Place** `Place` (same file): App, Site, Page, Command, File. Knowledge is replenished locally
  (§11).
- **Interface status** (`core/uistate/`): Control tree, element numbering and expiration detection,
  etc. Interface stability, action effects (`ActionEffect`, each action that changes the interface
  returns the actual effect, input is read back for verification; no changes can be seen in points
  and gestures on the mobile phone, but the tree on this screen cannot read which part - the tree
  cannot be used, or a piece is missing and the point is not on any element - the type is still
  `no_effect`, otherwise marked `treeBlind`, the prompt changed to say "Not seeing changes does not
  mean that you have not clicked", not saying that most of them have not been clicked; the things
  done by type (reminder with no effect, multi-step early stop, statistics) remain unchanged; the
  short text "reached the limit" appears in the interface after the action - the number of times has
  been used up, the upper limit has been reached, come again tomorrow, the operation is too
  frequent, limit reached In the first category, prompt bars, pop-up windows, and page pop-ups are
  not counted if they are on the screen before the action is taken (`LimitNotice`) - `limitReached`
  and a stop prompt are written in the result, and multiple steps at a time stop at this step).
  Mobile phones and web pages share this set. Before and after each call to the dispatcher,
  `SurfaceLayer` is used to allow it to prepare and finish the interface (take a look before the
  action, compare the effect, and attach the interface after the action), and the tool engine itself
  does not recognize it (see §1). Use it directly when performing irreversible judgment and approval
  of the entrance, and when judging playback by screen reading (`ActionTarget`, find the target
  according to the description).
- **Visualization**(`core/visual/`): Picture frame number, `view_past_screens` review, picture
  difference. The screen is just for users to see what Mina is doing and for supplementary
  observation when the text cannot be read; work depends on the interface status.

## 6. Capability module

The ability is assembled by `CapabilityProvider` according to `RunSpec`
(`core/assembly/RunAssembler.kt`, `TaskTools.kt`), and is installed once at the start of each run:

- Press `spec.capabilities` to select capabilities; unattended running removes the tools such as
  query, confirmation, takeover, schedule creation, and image modification; read-only running only
  leaves read-only tools (plus the webpage opening address).
- The universal tools (`core/general/`) are always there: `ask_user`, `hand_to_user`,
  `handle_obstacle`, `end_not_done`, `wait`, `view_past_screens`, etc. Provided only when conditions
  are met: `request_confirmation` (only available if the user agrees before spending money; only
  available if there is a confirmation level: interrogation mode, when someone is present), memory
  tool (only available if "Use Memory" is turned on), knowledge and skill review (only available if
  there is a knowledge catalog, `LookupTools`), `schedule_task`, `spawn_agent`, `history_read` (only
  available if there is a running record and previous runs have been recorded).

### phone: mobile app

- **Virtual screen**: The task is to operate the App on a separate virtual screen, and the user can
  still use the mobile phone. The virtual screen is built by the built-in mobile assistant
  (`app/cap/phone/display/AgentDisplay.kt`, `app/cap/phone/shell/ShellService.kt`) and has the same
  size as the main screen; starting from Android 14, it has its own focus and does not affect the
  main screen input (the main screen focus may be temporarily lost on Android 12 and 13, and a
  prompt will appear in the settings; mobile apps cannot be operated under Android 12 and below, and
  chat, web pages and sandboxes are still available). Only connect and create a screen when you need
  to open an app or link for the first time, or use system commands or event listeners; do not touch
  it if you only use web pages or sandbox tasks. The target app mutes the sound on the virtual
  screen and restores the sound when taking over. To mute, set the `PLAY_AUDIO` of the App to
  ignore, and the rules will be muted before `app/task/AppMuter.kt`:Mina issues a startup (opening
  the App, opening another copy, sharing, opening the specified App or the link connected to only
  one App, opening the system action of the interface; The alarm clock and timer that have been
  quietly set are not silent), and there is no sound in the first few seconds; if the startup is
  rejected, they will be restored to the silent state in advance; after that, every new app that
  pops up on the Mina screen (clicked from the target app, another copy on top, and the task at the
  top of the Mina screen will be reread according to the accessibility window change,
  `AndroidScreen.foregroundApps`) will be silent for the first time. To mute, press the "Package +
  User" function (`AppAudioKey`: the original copy is the package name, and the other copy is
  "Package Name@User Number", several interfaces of built-in assistant `*InUser` have `--user`), the
  copy in the avatar and work profile is muted independently, not together with the original copy in
  the user's hand. Unquiet: Mina herself; system package without desktop entry (system interface,
  selection box, permission box); What the user can see on the home screen at the moment (Assistant
  `visibleOnMainScreen`, press and use when the home screen cannot be seen clearly) - the mute
  button works, and there is no sound when the user's hand is muted. This kind of photo prompts "The
  App may make a sound" on the progress notification, and the same is true when the mute command
  fails. When mute is turned off in settings, no one is silent. At the end of the task,
  `AudioMute.restoreAll` restores all the things that have been done this time, including those that
  were done in the middle; The original mode before muting is recorded in the record file of the
  built-in assistant (`app/cap/phone/shell/AudioMuteLedger.kt`). When the assistant is started, it
  will first restore what it did not have time to restore last time. Restoration rules: It turns out
  that it is clear that allow/ignore/deny, etc. are written back as they are, and those that have
  not been set are written as allow and not default (Android only recognizes allow before playing
  sound), and only the packages in the record are moved.
- **App at the end of the task**: It is left by default and will not be destroyed with the screen.
  At the end (finally of `TaskRun`, before `AudioMute.restoreAll` restores mute) `AppsAfterTaskEnd`
  hands over the mark of the model (`phone_app_after_task`, stored in `AfterTaskMarks` of the mobile
  port) to the built-in assistant `finishDisplay`; the assistant determines the mark of each task
  according to `app/cap/phone/shell/AppsAfterTask.kt`: the mark is marked; the mark is turned off at
  the moment (ask first) `AudioManager.getActivePlaybackConfigurations`, the player status is
  started uid → package + user; when the uid is hidden, read `dumpsys audio`; those muted by Mina
  are also counted as playing; when they are not read, those being pressed silently by Mina are
  counted), otherwise they are left; Mina's own page, not the ordinary App page, will be closed.
  Move the remaining ones to the main screen and arrange them at the end:
  `WindowContainerTransaction.setHidden(true)` → `moveRootTaskToDisplay(…, 0)` (the system is always
  placed at the top, and will not be displayed or focused if hidden) → `reorder(…, false)` → Unhide;
  after moving, take a look at the ones that make a sound (move them back to the Mina screen without
  the mark "keep" and make a sound and turn them off with the screen), and then check that none of
  them are visible on the main screen (if they are visible, they will be placed at the back, if not)
  `removeTask`), finally release the screen. The `FLAG_DESTROY_CONTENT_ON_REMOVAL` screen is still
  attached, and the screen that has not been moved is turned off. This system will no longer be
  moved if it cannot be used in any step (the interface is not available or rejected), but it will
  still be shut down with the screen. A line of "apps after task:... move not supported" will be
  recorded in the diagnostic information (the details of `apps_after_task` are included in the
  operation record). The assistant remembers the left task number (only in memory); the next time
  the `startApp` (including another one) opens the same App, if the task is still behind the main
  screen and the user is not looking at it, it will move it back to Mina's screen and continue doing
  it without opening it again; cold start first close it and start it again. When only releasing the
  screen (`releaseDisplay`, `releaseAll` unbundled by the assistant), all the screens will still be
  destroyed.
- **Built-in mobile phone assistant**: an auxiliary process running as a shell, started by the
  mobile phone's own "wireless debugging" (`app/cap/phone/setup/BuiltinShellManager.kt`,
  `app/cap/phone/shell/HelperProcess.kt`; the ADB protocol is implemented by itself, in
  `core/adb/`). Pair once: The pairing code can be automatically read from the settings page by
  accessibility services, or entered from notifications or the setup wizard. The code filling
  notification (`app/cap/phone/setup/PairingNotifier.kt`, sent by `PairingCoordinator` according to
  the status of the assistant and the progress of finding the pairing window) does not include
  screen recording or floating window permissions, but only relies on the notification itself: the
  "pairing" channel is a high priority, has sound and vibration, and can pop up above the system
  pairing window; After the importance of the channel is established, the App cannot change it.
  `PairingChannel.plan` looks at the existing channel - if there is no channel, create a new one. If
  the old version is not high enough and the user has not changed it (`hasUserSetImportance`), it
  will be rebuilt with a new ID. The user can turn it off or adjust it to not pop up without
  changing it back. `SystemStatus.pairingChannel` will hand it over to the guide page
  (`SetupView.notifyIssue`): when notifications are not allowed or the channel is turned off, the
  main button of the pairing step is to allow notifications / Open the settings page of this channel
  and adjust it to no pop-up, only a reminder and entry. `PairingCoordinator.begin` starts watching
  for pairing (finding the pairing window, barrier-free code reading, and sending code filling
  notifications), the time limit is 10 minutes, starting from the last `begin` (`SlidingDeadline`):
  click "Go to wireless debugging", and leave the guide page when it stops at the pairing step
  (`ON_STOP`, most likely you cut out the system settings), you will adjust it again. If you are
  already watching, you will only push the time limit back, and do not start the notification again;
  The same goes for finding the mDNS (`BuiltinShellEngine.startDiscovery`) in the pairing window. If
  you adjust it while still looking, reset the timer. The boot page has been left open for a long
  time, and I cut out the system settings from recent tasks, but I can still read the code and pop
  up the notification. When you find the pairing window, send "Enter Pairing Code" (the barrier is
  only 5 seconds late when reading the code), and the action "Fill in Pairing Code" comes with
  RemoteInput (no unlocking is required, no generated reply is given); if no one fills it in, it
  will pop up again after 8, 20, or 40 seconds, up to three times, and the popup will ring again
  every time. If it has been replaced with other content, it will not pop up again. The code you
  fill in is first standardized in the local area (spaces, various horizontal lines, full-width
  numbers) and checked for 6 digits. If it is not correct, you will be prompted in the same
  notification on the spot and do not try; if it is correct, it will be immediately changed to
  "Pairing..." (it was always the same before pairing started, and it will not be counted according
  to the status before filling in the code). If it is successful, it will disappear after changing
  to "Pairing Successful"; if the code is incorrect, expired, or the window is closed, the same
  notification will say to get a new code and fill it again, and the input box will remain. When
  accessibility is not turned on, the guide page uses a three-line numbered icon to illustrate these
  three steps, and the bottom line of filling in the code directly on the page remains the same. The
  state machine of the setup wizard (`app/ui/PhoneSetupScreen.kt`) is a pure function
  `PhoneSetupFlow` (`core/shell/PhoneSetupFlow.kt`). The read switches are only used to
  automatically check and give prompts, and do not block them: developer options, USB debugging,
  accessibility, and wireless debugging are automatically checked and moved down after the four
  steps are read. When it reads that it is off, it stops at that step, but the three steps of
  reading the switch include "I have turned it on, next step" (recorded as `SetupInputs.confirmed`),
  and can be skipped without any obstacles (recommended: Mina can open it by herself after pairing,
  and will not be blocked by "restricted settings"). In addition to notifications and automatic code
  reading, the pairing step can also be filled in directly on the page, so no step will be stuck.
  After completing the steps, you can click back to redo them (`SetupInputs.revisit`, the step you
  go back to will automatically return to the original place when it reads from off to on). Success
  only depends on whether the assistant is running, regardless of what was read previously. When
  pairing or connection fails (the pairing window is closed, the connection cannot be made, the
  reason is unknown), press the developer options and wireless debugging to point out the step where
  the problem is most likely to occur (`SetupView.suspect`: the reading is off, or the phone cannot
  read), and give the button to return to that step; The user confirmed that wireless debugging was
  turned on, but the assistant read that it was like this when it was turned off and not connected,
  and it would not keep spinning. Automatically retry after disconnection (exponential backoff).
  Find the wireless debugging connection port (`core/adb/AdbConnectPort.kt`) and try in order. Stop
  when you connect: the port you last connected to (exists in the App private settings, and will be
  rejected quickly when it fails), system properties `service.adb.tls.port` (use it only after
  reading it), mDNS check `_adb-tls-connect._tcp` Get each local port (`NsdAdbServiceFinder`), scan
  the local port (`core/adb/LocalPortScan.kt`), and the port filled in by the user on the boot page.
  Rejection on TLS does not necessarily mean that the key is incorrect (some systems let another
  service receive the first TLS handshake on the same port first, rejecting all certificates, and
  then fall to adbd after reconnecting): try the same port again after a while, and if it still
  doesn't work, try other candidates. If the existing port is rejected, the machine will no longer
  scan; if we can't connect after trying, and another port rejects us, we will report
  `BuiltinShellFailure.AuthorizationRevoked` (re-pairing is required, the watchdog will not
  automatically retry). Scanning is a cover-up when the previous ones fail (some networks block
  multicast, and some systems do not allow apps to read properties): the wireless debugging adbd
  listening system randomly assigns ports, so it only scans the temporary port range (use it if you
  can read `/proc/sys/net/ipv4/ip_local_port_range`, otherwise 30000–65535), and only scans the
  machine's own address - 127.0.0.1 first, and no one like adbd is scanned again. IPv4 address on
  Wi-Fi, without touching other devices; `NioPortScanner` uses java.nio non-blocking connection,
  with a maximum of 200 connections at the same time, each waiting for up to 150 milliseconds,
  skipping the previously tried port and the monitoring that Mina has opened by itself
  (`OwnListeningPorts`, such as login callback); the open port sends CNXN and returns STLS, which is
  like adbd (it connects itself, and other services will not return STLS), and then complete the
  task as usual with the paired key. TLS handshake, the first connection wins and is recorded as
  cache; The total time of scanning and verification does not exceed 10 seconds, and it runs on the
  IO thread. When scanning, `BuiltinShellManager.scanningPorts` is true, and the boot page displays
  "Searching for the wireless debugging port on this machine...". When the connection fails, there
  is a step in the guide page to "fill in the wireless debugging port" (numeric keyboard, 1-65535,
  the whole paragraph is pasted with "IP address and port" and take the colon). Once filled in, take
  it and try again (`BuiltinShellManager.connectWithPort`, only use it this time; do not scan this
  time, and record the connection as cache). In addition to success, the guide page has "I'll talk
  about it later" at the bottom of every stage (waiting for user, connecting, failed), and you can
  always leave when you return, so the user is not trapped on this page. Several capabilities, "Turn
  on accessibility for me", "Not subject to power saving restrictions", "Automatic recovery after
  restart", "Automatically turn on wireless debugging" are turned on by default: they all rely on a
  paired connection to take effect, so the assistant becomes running every time (pairing completed,
  disconnected and restarted, paired when the app is started, automatically restored when starting
  up), and the `BuiltinShellRecovery.start` (`app/cap/phone/setup/BuiltinShellRecovery.kt`) looks at
  the status. `RecoveryPermissions.applyPending` (`core/shell/RecoveryPermissions.kt`) adds several
  items that the user has not turned off personally and have not yet taken effect, without popping
  up the box. If you fail, just write down a message to the settings page. Each record
  (`app/settings/RecoverySettings.kt`) is divided into two types: "effective" and "turned off by the
  user"; the old version is the same as before, and the ones that have not been turned off will be
  opened by default. Unpairing restores several items that have taken effect without changing the
  user's choice. When pairing again, they will still be automatically turned on, and those that were
  turned off by hand will still be turned off. Automatically restore after powering on to see if
  "automatic recovery after restart" has taken effect. For barrier-free access, only Mina's own
  services are added, and other services are left unchanged. Mina's accessibility service may be
  stopped for two reasons: after the process is killed by the system, the system treats it as a
  crash, and it is still enabled in the settings but is no longer bound; when it is forcibly
  stopped, the system directly removes it from the accessibility list. `BuiltinShellRecovery.start`
  At the same time, check whether there are Mina services in the system list, whether the service
  instance is connected, and the status of the assistant (all have callbacks, not polling); When the
  service has not been running for more than 3 seconds and there is a way to write the system
  settings (the App itself has the authorization to modify the system settings, just write it
  directly, without waiting for the assistant, wireless debugging can be turned off; if there is no
  authorization, wait until the assistant is running), `AccessibilityRepair`
  (`core/shell/AccessibilityRepair.kt`) opens it through `RecoveryPermissions.restartAccessibility`:
  if it is not in the list, add it directly, if it is not bound, remove it, stop and add it back,
  and the system will rebind; The repair will not be completed until the service is connected; If
  the addition fails, the original list (together with the main switch) will be written back, and it
  will still be counted as a failure. If the user has not unlocked it once after powering on
  (`UserManager.isUserUnlocked` is false), the service cannot be connected, so it will not be
  repaired at this time, and the status card only says that it will be restored after unlocking;
  after receiving `ACTION_USER_UNLOCKED`, it will be judged again, and it will be repaired as usual
  before it is connected. Regardless of who turned it off, it will be turned on automatically. It
  does not matter if "Turn on accessibility for me" is turned off in Mina; Try at most two times
  each time it gets stuck. If it still doesn't work, stop and leave the prompt for manual processing
  on the interface. Fixed it by only saying the time under the item on the settings page. When the
  task is connected to the mobile phone, wait for the accessibility service for 5 seconds as usual;
  when it is about to be repaired or is being repaired (`AccessibilityRepair.phase`), wait until the
  repair is completed (up to about half a minute), and then give up and report that the mobile phone
  is unavailable. Only enabled on Android 13 and above. Wireless debugging is no longer needed after
  the assistant is up, but restarting it (restarting the phone, Mina update, the assistant is gone)
  requires wireless debugging, and the original Android version will turn it off when Wi-Fi is
  disconnected and after restarting. "Automatically turn on wireless debugging": When the watchdog
  wants to start the assistant, but the wireless debugging is turned off, and it is connected to
  Wi-Fi, `WirelessDebuggingAutoOn` (`core/shell/WirelessDebuggingAutoOn.kt`) writes
  `adb_wifi_enabled` as 1 and then connects as usual; when Wi-Fi is connected (network callback),
  the assistant will do the same thing. Just turn it on but not off, don't touch USB debugging and
  developer options. It is only written by the App itself (it must have the authorization to modify
  the system settings, and it is shared with "Automatic recovery after restart", and it will be
  taken back when both are turned off; there is no other connection available when starting the
  assistant). If there is no authorization or the user turns off this item, it will not be written,
  and the interface will still be opened manually. The original version will immediately turn the
  switch back to 0 on a Wi-Fi that is not allowed and pop up a box to ask the user: After writing,
  it will stop after reading the dial back within 3 seconds, and the status will report "This Wi-Fi
  does not allow wireless debugging yet" (`BuiltinShellFailure.WifiNotAllowed`). The user must open
  it once and check "Always allow". The user will not write again until the Wi-Fi is changed, the
  user opens it by hand, clicks Retry, or the assistant wakes up. The box will not pop up
  repeatedly; writing is rejected, or 3 consecutive words are written. The assistant didn’t even get
  up and stopped; The interval between two writes is doubled from 10 seconds.
- **adb identity (pairing key)**: Mina's own RSA-2048 key (public index F4) plus a self-signed
  certificate (`core/adb/AdbIdentity.kt`). When pairing, the public key is given to the device.
  Afterwards, when the certificate is presented for each TLS connection, the device only recognizes
  the one during pairing. The key is stored in the App private directory with PKCS#8, and is wrapped
  with the AES key in the Android Keystore (`WrappedKeyFile`; `AndroidAdb.keyStore` will be deleted
  together with the packaging key when clearing). Reading back does not rely on the platform to
  provide a private key with CRT parameters: as long as it is an RSA private key, take the modulus,
  and use the public exponent in CRT form first. If not, read it from the RSAPrivateKey structure of
  PKCS#8. Press F4 to fill in the structure if it is also 0. Verify the signature before filling it.
  If it does not match, just assume that it cannot be read. Do not use the wrong public key to
  connect; the public key read from the same saved byte is the same as before, and there is no need
  to re-pair when upgrading. "Not saved" and "saved but unable to be read" are strictly separated:
  `AdbKeyStore.read` returns null for the former, and throws `AdbKeyUnreadableException` for the
  latter (cannot be decoded, the file is empty or truncated, or cannot be parsed). There is only one
  copy of `AdbIdentitySource` (`AndroidAdb.identities`) in a process, and the pairing (`forPairing`)
  and connection (`forConnect`) get the same one in the cache; if it has not been saved, create a
  new one and save it; when it is saved but cannot be read, the connection throws, logs (label
  MinaAdbKey), the file is not moved, and the engine reports `BuiltinShellFailure.KeyUnreadable`
  ("The key used for pairing cannot be read", the one-click repair is to re-pair, not automatically
  retry), only the pairing step will delete the one that cannot be read, replace it with a new one
  and save it (pairing will immediately give the new key to the device); unpairing (`forget`),
  deleting the key will also clear the cache. The `adb key status` line in the diagnostic
  information is the status of the key in this process (NotLoaded / Loaded / Created / Unreadable
  and the reason). It only looks at the status and does not read the key for diagnosis.
- **Use the computer to start the built-in assistant (USB)**: When the wireless debugging pairing is
  unsuccessful, or it is set by the Agent on the computer, the computer runs `adb shell sh
  /sdcard/Android/data/com.danish.harnessagent/files/usb-start.sh` through USB debugging. It starts
  with the same assistant (the same `app_process` command and parameters), and the same
  `HelperHub.offer` is used for handover. The shell identity, APK, Binder process, password and
  signature fingerprint are all checked. The script is written by `UsbStartScript`
  (`app/cap/phone/setup/UsbStartScript.kt`) in `Context.getExternalFilesDir(null)` (the app itself
  does not need permission to write it, the shell can read it, but other apps cannot). The text is a
  pure function `HelperHandoff.usbScript` / `usbStub`. Following `HelperHub.shell` rewriting, the
  password is also rewritten when it is completely invalidated (`HandoffNonces.resets`, such as
  unpairing) (`HelperHandoff.usbScriptWrites`; Otherwise, invalid passwords will be left in the
  script when the assistant is not around, and the handover will be rejected (BadNonce): When the
  assistant is not around (App startup, someone wants to use the assistant but there is no
  assistant, and the boot page is opened), write a script with a one-time password. The password is
  `HandoffNonces.usbNonce` - put it together with the startup password and the renewal password. Any
  number of used passwords will be invalid; according to the wall clock 24 After the hour has
  expired (it needs to be restarted), if more than half of the time is left, it will be used to
  avoid being replaced while the computer is running; When the assistant is connected, it is changed
  to a description without a password ("already running", if the process is no longer available,
  please open Mina first), and no usable password is left on the disk. The script only starts when
  `id -u` is 2000; the assistant uses setsid to create a new session and put it into the background
  sub-shell (the sub-shell returns to init after exiting). The password is piped into the standard
  input through the built-in echo, and the output is lost. Therefore, after the adb session ends and
  the line is unplugged, adbd kills its group of processes and cannot touch the assistant; The
  script waits for 2 seconds and then uses `pidof` to see if the assistant process is still there,
  and says "Starting, open Mina to confirm" or "Not up, open Mina first and run again." When you
  accept the assistant when it is not paired, the engine records "it is caused by the computer"
  (`BuiltinShellPlatform.usbStarted`), and the status is running as usual. The follow-up (adding
  those capabilities, turning on accessibility) is the same as after pairing: the `commandRunner`
  assistant runs commands through the assistant's `runCommand`, and the paired adbd is not connected
  until it is gone. The assistant started in this way will disappear after restarting; Mina cannot
  get up without pairing, and the new process will wait for a while (the assistant that is still
  alive will take over by itself). If it does not wait, it will report
  `BuiltinShellFailure.UsbHelperStopped` ("Mobile Assistant has stopped": run the command again on
  the computer, or pair the wireless debugging to let it automatically resume). It will
  automatically resume after powering on without touching the wireless debugging; unpairing will
  clear this record. The guide page usually does not provide this path, but only when needed
  (`SetupView.usbFallback`, calculated from `PhoneSetupFlow`) puts a card in the current step "This
  phone cannot turn on wireless debugging? Use the computer to start instead", which is a
  description, command and copy button. Required situation: There is no `adb_wifi_enabled` setting
  in the system (`SetupInputs.wirelessMissing`; the original Android 11 will always write it as 0 or
  1 when starting up, and it can be read whether it is on or off. If it cannot be read, it means
  that the system does not have wireless debugging, and no conclusion can be drawn if the reading is
  wrong); The user clicked "Can't find wireless debugging?" (the only one-line prompt for this
  step); the pairing window was never found or connected, and the assistant on the computer stopped,
  or the user said wireless debugging was turned on, but the assistant read that it was off; the
  user stopped at the wireless debugging step, pairing step (the pairing window has not been found
  yet) or automatic connection (`PhoneSetupFlow.stallPoint`) with no progress for 45 seconds
  (`SetupInputs.stalledMs`, changed the place and started from the beginning). The first two items
  need to be completed in the wireless debugging step (the developer options and USB debugging must
  also be completed before starting the computer). Once the card appears, keep it (recorded as
  `SetupInputs.usbAsked`) until success. Don't take "writing 1 and then dialing back 0" as a signal:
  the original version will also do this on Wi-Fi that is not allowed (a pop-up box will ask the
  user), and it will be a false alarm on a normal mobile phone; there is no separate public jump
  entrance on the wireless debugging page that can be used to judge.
- **After Mina is killed, the assistant pulls her up**: The system or cleanup tool ends Mina's
  process (when it is forcibly stopped, the system will also remove Mina's accessibility service
  from `enabled_accessibility_services`), and the suspension capsule disappears. The built-in
  assistant does not die: it learns from the death notification of the Mina token obtained during
  the handover (`HelperProcess.onAppDied`), and waits for `HelperRestorePolicy.GRACE_MS` (8
  seconds). During this time, Mina comes back by itself (the uid importance monitor reports it and
  re-handovers) and does not take action; If the capsule does not come back, it will be decided
  according to Mina’s last wish (`RestoreWish`, via `IHarnessShell.setRestoreWish`): the capsule is
  set to always display, or it will be pulled when there is a task running when it dies, otherwise
  (for example, the user forces a stop by hand, and the capsule is only displayed during the task)
  to let it stop. When pulling, first add Mina's accessibility service
  (`com.danish.harnessagent/com.meenai.mina.a11y.HarnessAccessibilityService`) back to the list,
  open `accessibility_enabled` (`RestoreCommands.accessibility`), and leave all other services
  unchanged; Only write this if the user has not turned off "Turn on accessibility for me" in Mina,
  and it is indeed in the list when Mina is alive. If the user turns it off in the system settings
  while Mina is alive, it will not be turned on for him - it will not be turned on for him until it
  is no longer in the list, so as not to force a stop to change the wish), and then re-handover as
  usual: when the provider used for Mina handover is taken, the system will start its process (the
  shell can take any App's The provider,`content` command uses this path; The provider is not
  restricted by the background startup service and does not display the interface),
  `MinaApp.onCreate` runs as usual, and the capsule, watchdog, and barrier-free automatic opening
  all follow. Pull it up to 3 times in 10 minutes. Once it starts to pull up, stop and wait for the
  window to pass; every step is recorded in the assistant's log (logcat tag MinaHelper). The wish is
  handed over by `RestoreWishSync` (`app/cap/phone/setup/`) in capsule mode, task running, "Turn on
  accessibility for me", any change in the accessibility list, or when the assistant is changed
  (`RestoreWishSender` is only handed over when the assistant is changed or changed). The assistant
  reports to Mina (`HelperRestoreReport`) "the last time I pulled up, the last few times, and the
  last time I gave up because I was too diligent" during each handover; Every time Mina starts,
  check whether the accessibility service has been removed or is in the list but has not been bound
  for 5 seconds (`A11yStartCheck`, only after the service is connected), and records it as
  `RecoverySettings`; both are written into the Recovery section of "Copy Diagnostic Information"
  (`restored by helper`, `accessibility on start`). When paired, Mina starts the assistant as soon
  as the watchdog is started (`BuiltinShellEngine.start`: If no one comes to handover, it will
  connect to the paired one after 3 seconds, without waiting for the task to be used), so that it is
  present; if it is not paired, it will not connect. Some systems will additionally turn off
  background apps. In the following sentence of Settings › Suspension Capsule, users are asked to
  allow Mina to run in the background and start automatically in the system settings.
- **Read and operate**: Accessibility service
  (`com.danish.harnessagent/com.meenai.mina.a11y.HarnessAccessibilityService`, source file
  `app/src/main/java/com/meenai/mina/a11y/HarnessAccessibilityService.kt` →
  `app/cap/phone/a11y/ScreenAccessibility.kt`) press the screen to read the control tree and make
  gestures on the specified screen. Every time you read the screen, first press the control tree to
  determine whether it is sufficient (`core/uistate/TreeQuality.kt`): if there are enough elements
  with names, most of the controls have names, and there is not a large area with no words in the
  App window, it will be considered sufficient and the text will not be recognized; If you are not
  sure, use the text recognition (`app/cap/phone/a11y/PhoneOcr.kt`) that comes with the APK to read
  it once. Add more than three lines of words that are not in the tree to count as a missing piece
  (partial); when a piece of the tree is missing or unreadable (unusable), the text must be
  recognized (the main model of the picture cannot be seen, the answer source and verification,
  `phone_collect` all rely on it); prevent the screenshot page from not being recognized. For the
  added words (`core/uistate/VisibleText.kt`), first remove the wrongly acknowledged lines (low
  confidence, all symbols, fragmentary Western numeric strings on the screen that are mainly in
  Chinese), and each line has a position of 0-999 and a click center; When the tree is not enough,
  the screen reading result will be written with `treeQuality` and a reminder: press the center
  point of the text on the screen, and use the picture to locate the controls without words (icons,
  pictures, input boxes). If you want to type into the box, enter its point or say which box it is
  in the element. The whole screen description is only used when you can't understand what the page
  is (it is several times slower, and it is not used to find a control). The main model can view
  pictures, and the screen is attached when "Read elements first, look at pictures if necessary" is
  turned on. Before recognizing text, wait for the screen to stop (the self-drawn interface almost
  never generates accessibility events, and the screen may still be transitioning when the event is
  quiet). During the recognition process, if you change the whole page, click on a new page to
  recognize it again. Use the same method for partial animation (animated avatar, cursor)
  (`app/cap/phone/a11y/StillFrameRead.kt`); even if the tree changes during the reading process, it
  will still be recognized. When the tree is not enough and no line of words has been read, the
  screen reading result reads `ocr` (empty, illegible, timeout, unavailable, failed), and the prompt
  is to use the image to locate the input box and button. Input is generally written directly into
  the input box, and the web page editing area is pasted with the clipboard. After use, the user's
  original clipboard is returned. When there is no input box with focus in the tree (the App draws
  its own interface), first select all and then paste via the clipboard. Ctrl+A / Ctrl+V is only
  sent to Mina's screen by the built-in assistant (`injectCtrlKey`). Then it is compared with the
  text recognized on the screen before and after typing (`core/uistate/UnseenField.kt`: one more
  occurrence is consistent, nothing before and after has changed to not being typed, and the rest
  cannot be verified; the words on the keyboard when playing the input method are not counted). The
  input method is not required to be played: the keys are sent to the window with the focus on this
  screen, and the frame can be received without the input method; the input method is not played and
  there is no change at all. It is probably because the focus is not on the frame, and `no_focus` is
  reported. Please click the input box first, and do not blindly re-paste. When there is nothing on
  the screen and the input focus is not found (the input focus cannot be found in each window), and
  the input method is not played, it will not stick at all. It will directly report `no_focus`:
  There is no frame to catch it. If the interface is stuck and moved elsewhere, it will be regarded
  as "possibly hit" (the same goes for clearing). To clear, use the Select All and Delete keys. When
  the input method is not played and the interface does not change, `no_focus` will also be
  reported. Input and clear also accept a point (coordinates, or `@vN` found by looking at the
  picture, just like clicking, first check that the screen has not changed): click on that point
  first, wait for the interface to be quiet, click on the input box visible in the tree, then type
  on it as usual, otherwise paste according to the above method, the text on the screen before
  pasting will be read after clicking; the report will not change at all after pasting.
  `point_not_typed` (that point is probably not an input box), does not automatically retry, and
  does not press Enter if it cannot be verified (`ScreenPort.typeAt` / `clearAt`,
  `AndroidScreen.typeAt`). There is no need to click and then type on the screen where the input box
  cannot be read: clicking only makes the box focus and neither the tree nor the screen moves. The
  verification of typing is the proof of click. When typing on a point, the click will pass the
  irreversible judgment (the click is a button click judgment of the send type). According to the
  description, point to the input box (`element`) but there is no matching one in the tree, and the
  tree on this screen is missing or cannot be used. When executing the step of changing the entry
  number, look at the picture to find this box (`Interfaces.bySight`, when equipped with the picture
  viewing model; check with `find_visually` in the same set, and mark those without evidence).
  Change the parameters to the found `@vN`, and then pass `@vN` to the model itself. Check the
  screen in the same way, judge it is irreversible, and check after pasting; As a result, write
  `foundBySight` and the words you see, saving you the round of searching first and then typing.
- **Gesture rhythm** (`core/cap/phone/GesturePacing.kt`, shared by `InputPacing` of random values,
  arcs, easing, and web mouse `core/uistate/Pace.kt`): 30–120 milliseconds pause before each finger
  drop; 50–120 milliseconds for click and hold, 40–90 milliseconds for each double click, and
  between two clicks 80–140 (within the time limit for double-click recognition by the system); when
  clicking on an element, the landing point will be a little offset near the center (no more than
  one-fifth of the width and height, at most 12 pixels, and the direction narrower than 24 pixels
  will not be offset, always within the element), and the coordinate point will be offset at most 3
  pixels. The start and end points of sliding (`phone_swipe`, `phone_scroll` back gesture, single
  finger `phone_gesture`) remain unchanged, and a short arc is walked between two points (the
  deviation does not exceed 6% of the distance, up to 30 pixels, and the multi-point path is
  followed as drawn), the duration fluctuates 8% above and below the given value, and the start is
  slow: shorter than 800 The speed when letting go of the fast slide of milliseconds is equivalent
  to the previous constant speed slide (the distance of the throw remains the same), and the slow
  slide of not less than 800 milliseconds ends with a stop and then raises the hand without
  throwing; the starting and end points are almost coincident (press and hold in place) only
  lengthen but not shorten. The speed requires each point to have its own time, which is not
  possible with accessible gestures (the system follows the path at a constant speed), so the
  rhythmic strokes are injected point by point by the built-in assistant (`injectStroke`, only sent
  to Mina's screen). When the assistant cannot pay attention, return to the accessible gesture and
  walk along the same arc; click and still use the accessible gesture. If it fails, the assistant
  will inject it again. Two-finger gestures only allow the duration to float. The control's own
  clicks, scrolling actions (`ACTION_CLICK`, `ACTION_SCROLL_*`) and input are not affected.
  `GesturePacing.Instant` is exactly the same as before (for testing).
- **Operation Rhythm** (Settings › Work › Operation Rhythm, `core/cap/phone/OperatingPace.kt`,
  exists `FeatureFlags.operatingPace`): Three gestures are like human beings, and the other three
  gestures are pressed - "Quick" (`FAST`: no pause between actions, typing once, no upper limit on
  the number of screens, which is the previous practice), "Standard" (`STANDARD`, default), "Like a
  human" (`HUMAN`: All Apps Press the slower one to stay and type). Each task is read once at the
  beginning (`TaskRun`). Evaluation and real machine acceptance are covered by
  `EvalHooks.operatingPace`. The default is "fast" (`-e evalPace` / `-e livePace` is replaced by
  `standard`, `human`). Do not change the settings; the core `toolDispatcher` defaults to
  `AppPacing.Off`, and `AndroidScreen` defaults `TypingPacing.Instant`, the test still runs fast.
  - **Typing** (`core/cap/phone/TypingPacing.kt` pure logic): `AndroidScreen.typeText` finds the
    input box and writes increasingly longer prefixes one by one according to the rhythm
    (`ACTION_SET_TEXT`, which is applicable to any native input box, including Chinese, Japanese and
    Korean characters). The last full text is still handed over to `typeAndVerify` to write the
    entire paragraph, read it back for verification, and try again. 60-170 milliseconds per word for
    Latin letters, 160-380 milliseconds per word for Chinese, Japanese and Korean characters, 1-3
    consecutive words are displayed on the screen together (like input method word selection), and
    there is an occasional pause of 0.3-0.9 seconds after punctuation and spaces; when there are
    many words, there are several words in one paragraph, up to 40 paragraphs, and the total time is
    up to 6 seconds (apps and "people-like" that limit the speed of operation: 90-230 / 220–520 ms,
    pause 0.5–1.4 s, up to 60 segments, 12 s). Not splitting a word (surrogate pairs, diacritics,
    variant selectors, zero-width concatenated emoticon sequences). Read back after writing the
    first paragraph: the box does not accept half of the character (the readback does not match or
    cannot be read out) or stops writing if it cannot be entered, or writes the entire paragraph.
    The password box, the web page box that can only be received by pasting
    (`TypingPacing.forField`), and the box that cannot be seen in the tree (paste) are still written
    at once.
  - **Stay between actions** (`core/cap/phone/AppPacing.kt` is pure logic, `AppDwell.kt` is
    connected to the phone's capabilities): Each action that changes the screen goes through the
    only execution entrance `ActionExecutor`. It asks the screen's capabilities before execution
    (`Capability.beforeWrite`, the phone's capabilities press `AppDwell` to stop), and tells it the
    result after execution (`afterWrite`); single step, `do_steps` Every step and playback in a
    batch is the same. The combined action (`phone_run` type) itself does not stop, and each step in
    it stops separately; the web page still stays on its own page (`PageDwell`) without passing
    through here. The effect of the previous action is that `done` or `place_changed` has switched
    to a new screen, otherwise it is the same screen: the same screen pauses for 0.5–1.2 seconds as
    standard, the new screen pauses for 0.9–2 seconds, and there is a 5% chance of an additional
    pause of 0.6–1.5 seconds, up to 3.5 seconds at a time; Apps that limit the speed of operation
    (App knowledge `pace: careful`, according to the package name `GuideCatalog.appPace`) and "Like
    a Human", the same screen lasts 0.9-2.2 seconds, the new screen takes 2.5-5 seconds, and the
    number of words on the screen recently read by the model adds 15 milliseconds to the reading
    time for each word (up to 8 seconds). There is a 10% chance of stopping for an additional 2-5
    seconds, up to 15 seconds at a time. When the pause is counted from the completion of the
    previous action, the time spent by the model thinking and reading the screen is included, and
    only the insufficient part is made up; the duration of the pause and the interval between
    external actions are recorded as the wait for this call (running record `tool_result.waitMs`),
    and the model is not informed; if the user interrupts during the pause, the action will not be
    performed, and `skipped_for_new_message` is reported. If the slow one stays on the new screen
    long enough and the next action does not follow the position on the screen (return, press keys,
    press direction scroll), press `IdleScroll` while staying. The arrangement is to slowly draw a
    small section in the middle of the scrollable content that takes up more than half of the screen
    (`AndroidScreen.idleScroll`, drag slowly, stop and then raise your hand; do not draw when the
    input method is playing, the input box is holding the focus, it is covered by other application
    windows, and there is not enough scrollable content). After a few strokes, the running record is
    entered (`idleScrolls`), and the screen in the model's hand is marked as outdated
    (`ScreenSession.movedByMina`).
  - **Maximum number of screens**: Apps that limit the speed of operation can switch to a new screen
    up to 24 times in one task (`AppPacing.screenCap`, scrolling down to read the same page does not
    count). After that, the actions of changing screens in the app (click, swipe, return, keys,
    gestures) are not performed, and the report is the same as the web page `page_limit`
    (`core/uistate/DwellStop.kt`. In the description, the model is asked to first read the answer.
    If you want to continue reading, use `ask_user` Q); Typing, clearing, scrolling, turning the
    wheel, and opening other apps are as normal. The count will be restarted after the user answers
    the question (`Capability.onUserAnswered`); the external action itself is not blocked. After it
    is completed once in this App, there is no upper limit for this task (via
    `Capability.onOutwardEnd`). There is no upper limit for ordinary apps.
- **Tools** (`core/cap/phone/`, `core/uistate/tools/`):
  - Basics: `phone_observe`, `phone_list_elements`, `phone_tap`, `phone_type_text`,
    `phone_clear_text`, `phone_scroll`, `phone_swipe`, `phone_back`, `phone_press_key`,
    `phone_launch_app`, `phone_share_text`, `phone_open_link`, `phone_list_apps`,
    `phone_screenshot`, `phone_diagnose`. Vertical `phone_scroll` prefers a scrollable control and
    falls back to a gesture only when none is found.
  - Query and compound: `phone_query` (search by text, resource ID, control type in the complete
    control tree, read only text), `phone_run` (a series of ordered commands, each step is
    repositioned on the current page, and you can decide whether to continue based on text
    conditions; the target is missing, cannot be distinguished, and switches to another App Stop and
    return the model when necessary), `phone_collect` (read a long list screen by screen or chat and
    press overlap to merge), `phone_find` (scroll to find the target), `phone_extract` (visible
    text, list rows, form fields; password does not return a value), `phone_dialog` (read Pop-up
    window, click only when unique button text is given), `phone_wait`, `phone_event` (wait or
    collect window, content, notification event), `phone_gesture` (single finger path or two
    fingers), `phone_set_wheel` (turn the scroll wheel to a value, see below).
  - System actions: `phone_set_alarm`, `phone_set_timer`, `phone_show_alarms`,
    `phone_add_calendar_event` (see below).
  - Others: `phone_system_command` (Execute a system command through the built-in assistant,
    separated from the Linux sandbox; 10 seconds timeout; when the command is doing things that are
    done by specialized tools such as clicking, swiping, typing, pressing keys, and opening apps, or
    fails twice in a row, the results will remind you to switch back to the specialized tool; after
    this task is reminded, commands to do those types of things will not be executed
    (`use_phone_tool`, only which tool should be used), use the shell to share text (`am start` with
    SEND (without file) will not be executed. Commands pointing to `phone_share_text`, checking
    status, and reading settings are not affected. `core/cap/phone/CommandSteer.kt`) and picture
    viewing tools are shown below.
- **Share text to App**: `phone_share_text` Launch a piece of text according to Android text sharing
  (ACTION_SEND text/plain, EXTRA_TEXT, only to the specified package) to Mina's screen (built-in
  assistant `startShare`, and open the App to check whether the user is using it on the home screen
  and whether it is occupied by other tasks), and then wait for it to come to the front desk like
  `phone_launch_app`, and the system will ask which one to open (two copies are installed; this App
  When there are several pages for collecting sharing, the system asks which one to use (the window
  with the system package at the top will also count), write down the task. It only opens the
  sharing page and does not send: the recipient and the send button are clicked by the model on that
  page, and the sending is still confirmed by the irreversible operation. This App does not accept
  text sharing times `cannot_share`. If you cannot send messages or take notes in an app that cannot
  be read or typed, use it first (`core/cap/phone/AppTools.kt`, `AndroidScreen.shareText`).
- **System actions (alarm clock, timer, schedule)**: `phone_set_alarm` (hours, minutes, labels,
  repeated days of the week, vibration), `phone_set_timer` (seconds, labels), `phone_show_alarms`,
  `phone_add_calendar_event` (title, start and end, all day, location, notes) are handed over to the
  clock and calendar App (`AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER`) on the phone according
  to the Android standard interface. `EXTRA_SKIP_UI`, `ACTION_SHOW_ALARMS`, schedule
  `ACTION_INSERT`, the type directly states `vnd.android.cursor.dir/event`; Calendar permissions are
  not required, `SET_ALARM` required for setting the alarm clock is a normal permission), and there
  is no need to operate their interfaces one by one (`core/cap/phone/SystemActionTools.kt`, the
  intent is first written from `app/cap/phone/shell/SystemIntents.kt` into a Spec that has nothing
  to do with Android and then replaced with Intent). First, click on the package manager on Mina to
  check who is connected (`<queries>` in the list lists these types): there is no App to connect to
  the Times `not_supported`, and the model returns to the interface for operating the clock and
  calendar; There are a few times when users have set the default to use the default one. Otherwise,
  they first choose the one that comes with the system and specify it directly to that page. There
  is no "Which one to use" selection box on the screen. Then let the built-in assistant launch to
  Mina's screen (`startSystemAction`, only release these types of intents, and check whether the
  connected app is being used on the home screen and whether it is occupied by other tasks like
  opening a link; keep staring at the home screen before and after the action, and move it back to
  the home screen). When the page taken over requires permissions that the shell does not have (the
  alarm setting page of the clock requires `SET_ALARM`), the assistant instead issues a
  PendingIntent prepared by Mina: start as Mina, press Mina to check the permissions, and press the
  assistant to check the screen and background startup. It still falls on Mina's screen (the issuer
  clearly says that background startup is allowed since Android 14). Alarm clocks and timers should
  be set quietly: before and after setting the alarm clock, read the next alarm clock reported by
  the system (`AlarmManager.getNextAlarmClock`, wait up to 3 seconds). The resulting `check` is
  verified (the next one is it), earlier_alarm_first (the earlier alarm clock is blocked in front,
  so it cannot be seen), not_seen, unknown. The latter two and the blocked one prompt the user to
  check `phone_show_alarms` once; App But I opened my own interface `screenOpened`, please confirm
  the model on that page. Move the alarm list, new schedule, and other apps to the top and write
  down the tasks; the new schedule is not saved for the user, and the save button is clicked by the
  model on that page. Setting alarm clocks and timers is a single-use entry for a group of stored
  mobile phones (`Entry.Alone`, without expanding the entire group). New schedule is an entry for
  expanding the entire group, and the alarm clock list is in the group; the risks are the same as
  opening the App (only change things on the phone, no money, no confirmation).
- **Scroll wheel selector**: A scroll wheel that rotates one frame at a time, date, region, etc.
  `phone_set_wheel` (the wheel or the number of the item it displays at the moment and the value to
  be displayed) is called in the port at one time and rotates one frame
  (`core/cap/phone/SetWheel.kt`, `app/cap/phone/a11y/WheelDriver.kt`) without looking at the model.
  The number given can be the scroll wheel, or it can be a certain item it displays (the scroll
  wheel you draw is often the selected item): at this time, the scroll wheel is found in the column
  aligned horizontally with this item (no less than three items, with similar spacing,
  `WheelMath.column`). Other scroll wheels (the minutes next to the hour) in the same block are not
  read or dragged; The scrollable container found above must cover the center of this item
  horizontally before it can be scrolled without obstacles (the scrolling area of the entire page
  does not count). Try the controls in order according to what they can do: if there is an input box
  in the scroll wheel (the original digital scroll wheel has it), just write the value into it, let
  it lose focus and confirm (so the pop-up input method is put away), and read it back; It can read
  out the current value by "rolling forward/backward" (one click of the original digital scroll
  wheel) without any problem. Press `WheelSteps` to roll up to 5 times in one batch. Each batch is
  read back and recalculated. The direction of the numbers is determined by the difference. It can
  be seen that the cycle (...58 59 00 01...) is on the side that is approaching. When reading back
  for the first time, correct whether "forward" becomes larger or smaller. The value does not move:
  it cannot be seen that the cycle ends on this side. The target is not within the range (`at_end`),
  It can be seen that the loop (without a head) does not work this time. It is handed over to
  dragging (`not_moving`). It can rotate more than one grid at a time. It crosses the target back
  and forth several times and then handed over to dragging, at most 80 times; Press the position
  difference of the text list target in front of you. If it is not there, swipe it to one side or
  vice versa; None of them work (the scroll wheel drawn by the App itself). Read the visible items
  in this column (recognize the words on the screen if you can't read them) and estimate the height
  of one grid and the current value. Place your finger on the horizontal center of this column, drag
  up from the bottom item (drag down from the top item to drag out this column), drag slowly
  according to the number of grids (no less than 1 second, when walking according to the rhythm,
  stop at the end and then raise your hand without shaking it, so as not to turn your head). After
  reading it back, calibrate the height of one grid according to the actual number of grids rotated
  and then drag it up, up to 5 rounds (`WheelDrags`); If the value does not move after dragging, it
  does not count as the end. Only if the loop cannot be seen and there is no item in the direction
  where you want to go, it will be `at_end`. Otherwise, change to fast swipe (0.4 seconds) and try
  again. If the drag is only one frame away, it will report `not_moving` three times without moving,
  prompting the model to use phone_swipe to swipe on this column. Pure algorithms such as value
  recognition, direction, cycle, number of grids, and how high one grid is are in
  `core/cap/phone/WheelMath.kt` (`"09"` is `"9"`, `"40 minutes"` is `"40"`; The accessible name of
  the selected item often contains the status word, `"selected, 21"`, `"Selected, 21"` is `"21"`,
  which is taken when the whole paragraph has only one number; the text value is also divided
  according to the segment ratio of punctuation; the stateDescription separately reported by the
  system is removed when it is spelled into the name; When only one item is marked isSelected, it is
  selected. Otherwise, the item in the middle line is covered by its own upper and lower ranges. If
  it is not covered (some rollers only report the upper and lower squares), press the two numbers
  next to each other to push out the middle square (39, 41, 40 in the middle, 59, 01 in the middle,
  00, 11, 1 in the middle, 12 in the middle, `WheelMath.between`). If it cannot be pushed out, it
  will return to the one closest to the midline; After each scrolling, dragging, and input, wait
  until it stops (the values are the same twice in a row at 0.35 seconds) before reading again.
  After reading the target, it stops and then checks again. If it is not correct, continue to turn.
  Only when the stopped values match are they reported; the `value` and `from` reported to the
  model only leave the value segment). The results are given to `value` (currently displayed),
  `from`, `attempts`, `method` (text, actions, drag). It is not transferred to the times
  `wheel_at_end`, `wheel_unreadable`, `wheel_not_moving`, `wheel_not_reached` and the values
  currently displayed are given accordingly. The scroll wheel-like node mark in the screen reading
  tree is `wheel: true` (original digital scroll wheel control, which may scroll, with three to nine
  short-term values arranged vertically and equidistantly in the center. It is better to miss
  them, `WheelMath.looksLikeWheel`, `ScreenReader.isWheel`). Multiple steps can be entered at one
  time, and several rollers (hours and minutes) can be set in one batch.
- **App installed in two copies**: When the same App is installed in two users (avatar, work
  profile), some systems will ask which one to open first when opening it. After `phone_launch_app`
  is opened, the top window is replaced by the system package window. The clickable options in the
  window mention the name of the App. Even if it is opened, the result will be `chooser` (option and
  which one is another) and a prompt: Select according to the user's instructions. If not, select
  the original one and do not open it again. The window is not cut out in the foreground. After
  selecting the App, go to the foreground and write down its tasks (`core/uistate/LaunchChooser.kt`,
  `AndroidScreen.openApp`). Another user's name next to the option is still read as `pm list users`.
- **Open another App**: Many systems only ask which one to open on the desktop icon. If they don’t
  ask when the program starts, the other one will never be opened. so`phone_list_apps`Give each copy
  another separate item (the same package name, write the name as "App name (another copy: the name
  of the user)", with`user`bring this`phone_launch_app`Just open that copy; if you don’t bring it,
  open the original copy. Which users can use it is determined by the built-in assistant according
  to the Android multi-user concept (`user`, read the UserInfo and UserProperties of UserManager,
  and mark them with the name given by the system itself:
  reflection`core/uistate/AppCopies.kt`,cannot read`UserInfo.flagsToString`The name and parentId in
  brackets, do not hard-code the tag bit): the type is private, the tag name is HIDDEN or PRIVATE,
  the system indicates that it is hidden from other apps (a category of private space), and the
  guest/demo/temporary/deactivated ones, will not be listed or opened; the rest must be running and
  unlocked, not in quiet mode, connected to the current user (the same data group or the superior
  user is the current user), and can be seen as clones (the type is clone, the tag name is the
  current user) CLONE, or share media with superiors) or original work materials. If you cannot read
  any item, it will be considered as unsatisfactory. Some systems make clones into complete users
  with clone tags (the type is not the original clone type), and they are identified by the tag
  name. Built-in assistant`dumpsys user`Gives the available user number and a one-line definition of
  the diagnostic information (only number, tag and tag name, type, data group,
  superior),`appCopyUsers`Check again before starting, if not included, return 8(`startAppInUser`).
  First check the startup page of that user in another user (`copy_not_found`, can’t change the
  question`queryIntentActivitiesAsUser`), first take the path to open the icon on the desktop:`cmd
  package query-activities --user N`The interface behind it; the system checks cross-user
  permissions based on the caller, the shell has INTERACT_ACROSS_USERS_FULL, and the package name
  matches the uid), with ActivityOptions to boot to the Mina screen - some systems will always
  prompt "Which one to open with" for the other copy of the program to start, but the desktop path
  will not be played; if it does not work, then use that userILauncherApps.startActivityAsUser, and
  finally return `am start --user N --display D -n component`. When the system still asks on the
  Mina screen (the option names may be exactly the same),`LauncherApps.startMainActivity`Click the
  options one by one and read the user to whom the top task belongs: if it is the one you want, it
  will be opened; if it is not, press Return once to let it go to the background (it will not end,
  it may be the one in the user's hand), open it again and click the next one; if it is not, it will
  fail.`startActivityAsUser`, tell which one was given, and don’t regard the wrong one as a success.
  When the target app is installed twice, each result will have`phone_launch_app`(The user who owns
  the copy on the screen at the moment), bring it when you want another copy`copy_not_opened`;The
  one that was issued was the one that I clearly said I didn’t want. Occupancy is counted by
  "package name + user" (`ShellService.sameCopy`): when the user is using the original copy on the
  home screen, Mina is not blocked from using the other copy, and vice versa; when the task's user
  cannot be read, it counts as occupied. A cold start only runs `am force-stop --user N` on that
  copy. The target app records the user the task belongs to (`TargetApp.userId`), and if Mina's
  screen is switched to another copy of the same app it also counts as taken over; parallel tasks
  occupy the two copies separately; muting applies per "package + user", so when the target is the
  other copy only that copy is muted and the one in the user's hand is left alone. Reading the
  screen, tapping and typing follow the screen, so nothing else changes.
- **Open the App on the user's home screen**: When the App on Mina's screen opens another App not
  from its own page on this screen, but from the background (service, jump, system selector,
  cross-user transfer), Android puts it on the home screen by default (when two copies are
  installed, it first asks which one to open on the home screen), and no change is visible on Mina's
  screen. Please use the built-in assistant to stare at the home screen for 6 seconds
  (`watchMainScreen`) before each click, slide, key press and other actions
  (`AndroidScreen.verified`): the assistant registers the task stack listener (ITaskStackListener)
  with the system, only as a "changed" signal, and re-views the task list after receiving it; if
  registration fails, every 100 seconds during the staring period milliseconds), write down the task
  that was replaced at the top of the main screen, which screen it was on before, and which user it
  belonged to (`app/cap/phone/shell/MainScreenWatch.kt`, `MainScreenLog.kt`), and look again on the
  spot when querying (`mainArrivals`). After the action, the judgment is based on pure logic
  `MainScreenSpill`: the ordinary App page that popped up during this period (not the desktop,
  recent tasks, not Mina itself and the desktop App), was not moved from another screen (takeover),
  and is still on the home screen at the moment. During this period, the user has not clicked
  anything on the home screen (unobstructed click event), nor is the original top one exposed again,
  so it is moved back to Mina's screen (and the call to take over the same move task) `moveTask`).
  If the pop-up windows that pop up on the home screen this time are login verification, account
  confirmation, payment confirmation and other types of pop-ups that need to be handled by the user,
  do not move: the page of the system account login service (a package that has registered an
  account authenticator and does not have a desktop icon, `AccountManager.authenticatorTypes` only
  counts if the LAUNCHER entrance cannot be found every time it is read; the only pages that pop up
  on the home screen are pages such as adding an account, logging in, and verifying; Authenticator
  packages with desktop icons, such as chat apps and mailbox apps that have registered accounts for
  synchronizing contacts, do not count, and follow other criteria as usual,
  `MainScreenSpill.authenticatorUi`; the authenticator service is checked in the list to make such
  packages visible to Mina), the top window is not a full-screen dialog box, the bottom pop-up
  layer, there is a password box in the window, or the page itself does not enter recent tasks
  (`excludeFromRecents`) (`AndroidScreen.needsUserWindow`; The width or height of the window must be
  less than 80% of the main screen to be considered a full screen. View it again every 300
  milliseconds, so as not to regard the ordinary App that is still in the opening animation as a
  pop-up window.), all remain on the user's screen, reporting `onUserScreen`, `needsUser`, and
  prompting the user to process `hand_to_user` there; After the pop-up window appears, the user has
  clicked on it (it is the package of the barrier-free click event) and will not move it
  (`MainScreenSpill.Tap`). **Mina never opens or reopens apps or system pages for other apps**: The
  app that is opened must always be the original app (for example, if a shopping app opens a payment
  app, the payment app must identify who opened it). If you move the task without changing the app,
  it will change when you reopen it, so you can only move it and not reopen it. Before the action,
  the page was changed in the task at the top of the home screen. The App that the user was using
  was opened, so it should not be moved; it would not be moved during the takeover. The system asks
  which one to open on the home screen (the window of the system package is the system selector, or
  the options in the window match the name of the App that is installed twice; the selection box of
  each system is not necessarily the original one, `MainScreenSpill.handling`) **Does not move**: If
  the screen is moved, it will be rebuilt as a new screen, and some will be closed by themselves
  during the rebuild. It remains on the user screen, with the action result `opened` (picker,
  onUserScreen), the recognized option `chooser`, and a prompt: The system asks on the user screen,
  don't click again, don't choose for the user, the next action will wait for the user to finish
  selecting. The task stops before the next action of changing the screen (`TaskRun.awaitUserPick`
  is connected in front of `TaskGate`, `AndroidScreen.awaitUserPick`). The status is waiting for you
  to process (`GateReason.needsYou`, `CapsuleKind.NeedStep` on the capsule, "Need you: "<App>" is
  asking which one to use, please choose on the screen"). During this period, you have been staring
  at the main screen; After the user has made the selection (the selection box is no longer there),
  look at the selected one (`MainScreenSpill.picked`): open the normal page on the home screen, move
  it back to Mina's screen, continue the process, and report inProfile; the ones opened on Mina's
  screen are also included; the system requires the user to handle it manually (there is a password
  box in the window, or when the main screen cannot read anything in the tree, the system will
  report a screenshot window, such as payment and password input; Apps that cannot be read in the
  tree are moved back as usual, `MainScreenSpill.needsUser`) remains on the home screen, and are
  changed to a pause that requires you to process and you can click to continue (""<App>" requires
  you to process it on the screen, click to continue after processing"). After the user clicks
  continue, it will be moved back to the home screen if it is still on the home screen. If it is
  closed, tell the truth (`byUser`); The user closed the question (`closed`) and told the truth, and
  the model decided the next step. These results will be displayed as `opened` in the next screen
  reading. If the next action is not performed, `opened_elsewhere` will be reported. `opened`
  (package, app, pulledBack, onUserScreen, needsUser, inProfile, picker, closed, byUser) in the
  results is accompanied by a prompt: if you move it back, don’t click it again, and continue
  working there; what is moved back is counted as the target App is open, not cut away, and the
  effect is that it has been moved to another place rather than having no effect; what is left on
  the user screen (the user is currently using it) says not to come back, and asks the user to deal
  with it; The pop-up window that requires the user to handle it by hand says not to move it back,
  but please click there (`needsUser`). With two copies of the app installed, after asking which one
  to open, which one was reported to be opened (`inProfile` main/other, according to the user to
  whom the task belongs), when the other one was prompted, the user was prompted to go back and
  choose the other one if he didn't want it. The action was started after a while. Move it back
  before reading the screen next time, and `opened` will appear in the screen reading. The next
  action will not be performed, but `opened_elsewhere` will be reported. A batch of actions and
  mobile scripts will stop when they encounter it.
- **Takeover**: When the user needs to handle it manually (login, verification code, etc.),
  `hand_to_user` sends a notification; the user clicks "I will handle it", and the App on the
  virtual screen moves to the home screen; after the processing is completed, click "Handle" or "I
  finished it myself" to move it back, and the Agent re-reads the interface to verify before
  continuing; "Skip" does not count as completion (`app/takeover/Takeover.kt`). Takeover queuing of
  multiple tasks.

### web: built-in browser

- WebView is rendered on the App's self-built private virtual display (`app/cap/web/WebHost.kt`),
  which does not occupy the mobile phone screen and does not require a built-in assistant; the
  desktop version page (`app/cap/web/cdp/DesktopProfile.kt`) is used by default, and the desktop
  version or mobile version (`web_site_mode`) can be remembered according to the website. When the
  user selects the interface language in Mina (not following the system), the web page is requested
  in this language (`app/cap/web/cdp/PageLanguage.kt`): after the acceptLanguage of
  `Emulation.setUserAgentOverride` is changed to Accept-Language and navigator.language(s) (such as
  English → `en-US,en`), the desktop version is added to the desktop identity, and the mobile
  version copies WebView's default UA and UA-CH, only change the language; the first page is sent
  before the session is set up, and is reloaded once after setting up. The label of the language
  changed midway will be changed before the next main document is loaded; when following the system,
  it is not overwritten and the string given by the system is sent.
- Through WebView's DevTools protocol operation page (`app/cap/web/cdp/WebDevtools.kt`), clicks and
  inputs are real input events; when the connection fails, the injected page script is returned.
  Never turn on the Runtime group (`Runtime.enable`): when it is turned on, the page will notice
  that the debugger is hanging, and some websites on the real machine will always be reloaded;
  reading scripts and page scripts only need to isolate the world and evaluate (the mina call of the
  page script is returned by the outbox in the world, and is returned by the always-hanging fetch
  evaluation, without turning on the Runtime binding). The world is not recognized by the main frame
  changing the document (`Page.frameNavigated`) and the evaluation report context is no longer
  there.
- In the element tree of the screen reading script (`app/src/main/assets/web/page.js`), the password
  box, as well as the autocomplete text input box and textarea marked with bank card number,
  security code, validity period, and one-time verification code, only report the number of entries,
  without values; the words in this textarea are not included in the text of the reading page and
  abstract. The verification after typing is done by reading and comparing the values in the App
  as before, and is not handed over to the model.
- Tab pool: up to 4 per task, up to 8 globally (`app/cap/web/WebTabPool.kt`, `TabBudget.kt`). The
  link that the page wants to jump to the external app will not jump.
- Download `downloads/` that falls into the current conversation workspace, and it will not be
  installed if it is not opened; upload only the files in the workspace (`WebDownloads.kt`,
  `UploadStaging.kt`).
- Read-only script: `web_evaluate_js` runs in the isolation environment of the page. The page object
  is a read-only wrapper. Writing, clicking, and jumping are all blocked, and the Internet (fetch,
  XMLHttpRequest, sendBeacon, WebSocket, EventSource, new Image()) is reported as blocked_network.
  Any request it makes when seen on DevTools also ends with an error; the data behind the page is
  `web_capture` Read the response received by the page itself), the secret input box such as the
  password box reads `***` (the same is true for iframe, shadow root, and template). On their own
  interface, only the attributes set by the page such as type and name are read. The innerHTML class
  and the textContent class of the outer element containing the secret textarea are coded on a copy
  that does not belong to any window (custom elements that do not construct the page). The secret
  textarea The child text node and the value attribute node of the secret input box are also read as
  `***`, and the same is true when leaving the page after getting it; selecting elements according
  to the value attribute reports invalid_selector, and the selector is first restored according to
  CSS rules (escape, comment, namespace) and then judged. Those with `url(` are all blocked, and
  `web_script` and `mina.query` use the same set (`core/cap/web/ReadScript.kt`,
  `SelectorPolicy.kt`). Site commands (scripts with `/* mina-command` headers in website knowledge,
  `core/knowledge/SiteCommands.kt`) can also be run read-only through `web_run_site_command`;
  currently there are no site commands in built-in knowledge, and this tool does not appear. The
  script run by `web_script` cannot get the page and can only operate through Mina's action entry.
- Login status: Website registration form `SiteRegistry` records the sites visited, whether you have
  logged in, and whether you have been blocked by verification; Settings → Website login can open
  login, single site exit, and all exit (`app/cap/web/WebLogin.kt`). Web page takeover moves the
  same WebView to the full-screen page of the main screen (`app/web/WebTakeoverActivity.kt`),
  leaving the form and login process intact.
- The user's own browser (sidebar "Browser", interfaces at `app/ui/BrowserScreen.kt`,
  `BrowserStart.kt`, `BrowserPageParts.kt`, label at `app/browser/UserBrowser.kt`): a visible
  `WebTab`, and the same web page configuration (Cookie, web page storage) as the Agent label. When
  logging in to the Mina service here, it is also logged in. Usually it does not enter the tag pool
  of any task, does not occupy the tag limit, and does not go to the web page to take over the
  session (`WebHandover`). The tags and takeover of tasks proceed as usual; The user asks Mina for
  that task when asked on the page (see next item). **Identity** (`BrowserIdentity`): Browse the
  mobile version by default; change the website to the computer version in "More" and record it as
  `UserSiteModes` (`browser_site_modes.json`, the subdomain name follows the superior). Next time,
  you will also use the computer version and save it separately from the Agent's share
  (`SiteModes`); To open a website that has not been logged in from a commonly used website is "Log
  in for Mina": the entire label is the same as when Mina is working
  (`WebDevtools.agentWantsMobile`, the default computer version), the cookies left after logging in
  are the same version of the website that Mina sees when working, and there is a banner on the top
  "Log in for Mina..." with "Logged in" (use `LoginProbe` to take a look: if you see that you are
  not logged in, you will be prompted, if you see that you are logged in or if you can't see it, you
  will trust the user, Mark as logged in, return to home page), After the page was loaded, I looked
  at it as usual. I saw that I had logged in and the banner was closed. The rules are only installed
  on this label (`WebDevtools.setModePolicy`, one copy for each label, press `SiteModes` when null),
  and the identity of the Agent label remains unchanged. This label is seen and clicked by the user
  personally (`WebDevtools.setHandOperated`): In the computer version, UA, UA-CH, and identity
  patches are still the same, but no viewport simulation is added (the Agent label uses it to indent
  the whole page into the view, and the zoom is fixed), and it is given to WebView's own wide
  viewport and overview mode. When it is opened, the whole page is indented into the screen, and two
  fingers can zoom in (`DesktopProfile.viewportCall`); When changing the version, use `beforeLoad`
  to change the UA, wide viewport and simulation and then reopen the current page. When you click on
  another website halfway, use `onPageStarted` to change it according to the rules and reload it.
  **Start page**: The top bar only has return and "Browser"; a large search box ("Search with
  Bing"), and below it is Lenovo when typing (`StartPageModel.suggestions`: The first search for
  this sentence, if it looks like a URL, open it; followed by the recently opened title or URL that
  matches); "Commonly used websites" has four cells in a row (`BrowserFavorites.combine`): the ones
  added by the user are at the front, followed by the websites in the site registration form. The
  same website (registerable domain name) is in one cell, and the shortest host name is opened. Any
  host name of this website will be logged in with a small green dot (the following sentence is
  "Logged in, Mina can also use it directly when working"). The last cell "+" prompts to go to
  "More" to add; "Organize" or long press to enter the organizer, the icon shakes slightly (the
  animation will not shake when the animation is turned off), × remove, "Finish" to exit. Only the
  user's changes (`browser_favorites.json`: those added and removed by oneself) are recorded in the
  common use. If they are removed, they will not be displayed. The registration form, login status
  and cookies will not be moved (exit can be done in the "Website Login" on the settings page).
  "Recently opened" refers to the last 10 pages opened here (`RecentPages`; the same URL is not
  counted twice and only the most recent ones are kept). The search entry reads "Search: <term>". The web
  page is the website icon, title and website name. The right side is the relative time ("10 minutes
  ago", "Today 11:20", "Yesterday", "October 21st"), and the right side of the title is "Clear".
  Website name and icon (`SiteLooks`): After the page is loaded, read the website name
  (og:site_name, application-name) declared on the page. If not, take a section of the title (take
  the beginning of the homepage, take the end of other pages) and change it to a more reliable
  source; the icon is the website icon (`WebTab.Listener.onIcon`) received by WebView, reduced to at
  least 128 pixels and saved as PNG; All are recorded in the host name. If the registered domain
  name does not exist (or is worse), a copy will also be recorded. Other sub-domain names of the
  same website can also be recognized. When there is no such thing, the name can be taken from the
  registered domain name and the suffix is removed (shop.example.com → example). The box is based
  on the fixed background color of the host name plus the first character. **Web page**: The top is
  close (return to the home page), address bar (usually the lock and website name, the search page
  is the search term, the computer version has a small label of "PC version"; click to expand to the
  complete URL to change, "Cancel" to take back), refresh/stop and 2dp progress bar; The following
  is back/forward/start page/share (system sharing panel, URL plus title)/more. "More" panel:
  website (icon, name, host name), refresh, copy link, open in other browsers, add frequently used
  websites / remove from common, switch to computer version / switch back to mobile version (the
  same will happen next time for this website). The system return key first goes back in the web
  page. If it cannot go back, close the pop-up window and return to the page where it was opened.
  Otherwise, return to the start page. On the start page (exit them first during sorting and input),
  press it again to return to the place where you came in. The input in the address bar and search
  box is judged by `AddressInput`: anything with `://` is opened as it is; localhost and IP
  addresses get `http://`, anything that looks like a domain gets `https://`, and the rest goes to
  Bing search; before opening it passes `WebUrlPolicy.checkNavigable`. If it cannot be opened, give a
  prompt. After the page is loaded, wait for a while and use `LoginProbe` to check the login status:
  some websites in the registration form will be marked or removed according to the ones you see,
  and those that are not will be marked only after you see that you have logged in. The page pop-up
  box (alert / confirm / prompt / exit confirmation) pops up here to give the user an answer; a new
  window (clicked by the user, only opens one layer) is displayed on the current page, and the
  current page remains: "Log in with a third-party account" and other pop-up windows. After logging
  in, the results must be returned to the page where it was opened; When the pop-up window closes
  itself, it will return to the current page; links to Apps will not jump, and downloads will not be
  accepted temporarily, and a prompt will be given. The web view is placed in the process, continues
  to be used when the screen is turned, and is closed when leaving this page.
- **Ask Mina on the browser page** (`app/ui/BrowserPageAsk.kt`'s interface is the same as
  `PageChat`, `app/browser/PageLoans.kt`, `PageConversations.kt`, `PageAskModel.kt`): There is an
  additional "✦ Ask Mina, or let her do something on this page..." (PageAskBar) at the bottom of the
  web page (not the start page); when selecting text on the web page, there is an additional "Ask"
  in the text selection menu. "Mina" (`PageFrame` includes a layer of web page kernel selection menu
  callback, click to read the selected word first and then close the menu), the selected word is
  hung on the input box, and is brought along with this sentence.
  - **How to write this sentence**: The first paragraph in English describes which page it is
    (`core/cap/web/AttachedPage.kt`: `[Web page I'm viewing in Mina's browser. It is attached to
    this task as your current web tab.]`, Title, URL, each line of the selected words starts with
    "|", `[End of page]`), and after a blank line is the user's original words. Save the
    conversation as it is, hand it over to the task, and this is what the model sees; only tasks
    starting from the browser page will have it (the same paragraph will be written when the newly
    selected word is included in the interjection). On the dialogue page, draw "webpage·website" and
    the selected word (SentPage) above the bubble. Titles, lists, notifications, and title models do
    not include this paragraph (`ChatCopy.plainTitle`, `ScreenCopy.plain`, `ConversationTitler`),
    nor do language prompts, answer language checks, and memorized citations
    (`WebCapability.userWords`). With its mission, a group of web pages (`WebCapability.preload`)
    are opened at the beginning; there is a paragraph "The user's page" in the web page capability
    description: "This page" is a borrowed tag, read it directly, do it directly, and no longer open
    its URL; the user looks at it and only changes it as needed; open other web pages and open new
    tabs.
  - **Which conversation should be followed** (`PageConversations.pick`): The questions asked on the
    same page (the same URL, excluding `#` and the ending `/`) will be followed by the most recent
    one, no matter how long ago; otherwise, the questions asked within 2 hours on the same website
    (same host name) will be followed by the most recent one (the same thing is still clicked all
    the way); no new conversation will be opened. The line above the input bar "Continue "..." · New
    conversation" can be replaced with a new conversation. The page from which the conversation
    starts is recorded in the `.page` file next to the conversation file
    (`ConversationStore.pageOrigin`, JSON: url, host, title; the old conversation does not have it,
    delete the conversation together). When asked again on the same page, change it to the latest
    page; there is a small "web page" label after the conversation title in the side column, and the
    lower line is the website (`DrawerConversation.site`).
  - **Borrow tag**(`PageLoans`, only in the main thread): When asked, first "agree to lend" to that
    dialogue (`UserBrowser.lend`), pick it up when the task starts
    (`TaskRun.execute`inside`UserBrowser.claim`, only tasks initiated by the interface; it can only
    be borrowed when the page is still open), and only one copy can be borrowed at the same time.
    After borrowing it, I built the web page on the spot (`LazyWebPort.prepare`), borrow this tag
    into the task’s tag pool (`WebStack.borrow`): It becomes the current label of the task, and the
    web tool reads it and operates on it, just like the label in the pool; but it is not linked to
    the web page display of the task (it is in the browser page), does not count the per-task and
    global upper limit, is not turned off by space allocation or memory recycling, and the pool does
    not accept its monitoring (downloads, pop-ups, and login probes still belong to the browser
    page). Open the URL in the task (`WebTabPool.attachExternal`)Do not change the user's page and
    open a new tab(`web_navigate`); When the user's page opens or closes the pop-up window, the
    borrowed one will be replaced with the visible one (`WebScreen.navigate`). Return it when the
    mission is over (`PageLoans.swapped`, before closing the web page), the page remains in front of
    the user; the pool closes it (`WebStack.swapBorrowed`Level, mission ends) and just return
    (event`PageLoans.end`), not destroyed. When the user closes the browser page (including "open in
    chat" to leave the browser page), the page is handed over to the task and then used
    (`PageLoans.closing` → `WebStack.adopt` → `WebTabPool.adopt`): the same WebView becomes an
    ordinary tab of the task and is attached to the web page display, so scrolling, form state and
    single-page-app state are not lost; when the task ends it is closed together with the other
    tabs, and only if the task does not take it over (it crashed) does the browser page close it.
    Browser page tab numbers are all different (`browser1`, `browser2`, …), and a page and its
    pop-up window can be lent to the same task one after another.
  - **The difference between the borrowed label and the Agent's own label**: It is viewed by the
    user personally (`setHandOperated`, the computer version does not include viewport simulation),
    the page coordinates are calculated according to its actual size on the interface
    (`WebScreen.viewSize`), and DevTools clicks are converted according to the visual viewport; when
    the user zooms in with two fingers, the full page must be retracted before reading the screen
    (`WebScreen.unzoom`: the visual viewport is narrower than the layout viewport or moved away)
    `zoomOut`, up to 12 times), the numbered coordinates must match the screen. The screenshot is
    not taken from the web page display, but the view is drawn directly into a picture
    (`WebScreen.viewFrame`, which corresponds to the view pixels one-to-one, excluding the dialogue
    panel covering it). Do not roll it lightly when staying (`idleScroll`). Picture-in-picture and
    capsule do not put the web page at the moment (`WebPreview`, click to accept the calculation).
    When it is left to the user for processing (human-machine verification, login), it is not moved
    to the takeover interface: it is already in front of the user, and is only locked until the user
    clicks "Processed" on the browser page panel (`WebStack.showForTakeover`, `returnFromTakeover`).
  - **Pause as soon as the user touches it**: The container of the web view
    (`PageFrame.dispatchTouchEvent`) only receives real touches sent through the window; Mina's own
    input does not pass through it (the mouse and keyboard of DevTools enter the page directly, and
    the native touch of `WebInput` is sent directly to the web view), so its own actions will not be
    regarded as user operations. Press your finger → `UserBrowser.userTouched` → Borrow its task
    (`TaskRun.userOnPage`): When you are working at the moment, the borrowed page is the current
    tab, and you are not operating on the mobile phone screen, `TaskGate.onUserOnPage` makes a note,
    and the pause is displayed on the spot (`publish`. The running display is `Held(paused, you are
    operating, Mina Paused)`), the next tool that will change the screen was previously stopped at
    the level (no matter what kind of screen); the user clicks "Continue" (`TaskService.resume`) to
    cancel it on the spot, and when the loop has not reached the level, it will be redisplayed
    according to its current state. The page will not pause when touching the page while waiting for
    the user to answer, taking over, etc.
  - **Interface** (PageAskOverlay, pure logic in `PageAskModel`): When the user asks, the lower half
    of the webpage is covered with a half-screen dialogue panel (PagePanel, 58% of the height of the
    webpage area). The webpage exposed above is the page that Mina is working on; when Mina is
    working, the page is surrounded by a lavender light edge (PageGlow, not blocking touch). The top
    of the panel is the website name, "Start from this page·Page title", "Open in chat" (the same
    entrance as the notification click `TaskService.openAppIntent`) and collapse; in the middle is
    the back and forth of this dialogue (the same set of dialogue cards as the floating capsule:
    `CapsuleThreads` row, ThreadList drawing, the task looks like `ChatViewModel.reduce`,
    `CapsuleModel.cardRunOf` push); while working, the following are the steps (`Steps`), each step
    is marked with "webpage" or "App" (the activity object of that step is "website..." or the
    package name); the buttons you want to click (answer options, agree, I will handle it, continue)
    are the same set of capsule cards (`Pinned`); the bottom is the input box: when running, it is
    to interrupt (the same path as the dialogue page, `TaskService.start` (with the number of this
    sentence), on the right is the stop button; when Mina asks you, it is the answer; after the end,
    it is to continue asking on this page. A page is "processed" when it is in your hands (under
    control). When the panel is retracted and the matter is not over yet, there is a small card
    (PageCard) at the bottom. Click to expand it; when you touch the page and pause it, the panel
    collects this card (writes "Paused" and "Continue"), with a message on top "You are operating,
    Mina has been paused and continued" (PausedBanner); when Mina wants you to reply (answer, agree,
    take over, move a hand), the panel opens by itself. The task is a complete Mina task: other web
    pages are opened in the task's own background tab, other apps are operated on Mina's background
    virtual screen, and the user stays in the browser; each time it is a normal conversation in the
    chat list, and the result is the same conversation and notification as other tasks. Close the
    browser page and continue working. Element highlighting is not done (only light edges).
- **Leave a page after the task ends** (`web_keep_open`, `core/cap/web/KeepOpen.kt`;
  `app/cap/web/PageKeeper.kt`, pure logic in `KeptPageSlot.kt`): By default, when the task ends, all
  labels of the task and its web page display are turned off (`WebStack.close`). When the user wants
  the page to continue running after answering (continue to play, hang up and close), the model
  calls `web_keep_open` (with reason; `keep=false` cancels) for that page, and only notes which
  label it is at `WebScreen.keepMark` at this time; the web page capability description states
  "Close the task after the task is over, leave the first mark, truthfully tell whether to keep it
  or close it in the answer." The page borrowed (the user's own) is not closed with the task, so it
  reports `users_page`.
  - **Hand over**: At the end of the task, `WebTabPool.closeKeeping` first takes out the page from
    the pool (`release`: does not destroy it, does not remove it from the display, and does not
    attach other tags to push it out), then close the other tags of this task; that page, together
    with the private display (`WebHost`) it is attached to, is handed over to the process
    `PageKeeper` (`WebOwner.pageKeeper`, by `MinaApp` (Give), the display will not be turned off -
    the page is still hung on the display during the task, and is visible as during the task, and
    the timer and playback will continue as usual, without any other actions. After that, it is no
    longer in the tag pool of any task, and other tasks cannot be listed or turned off; it is
    counted into the global tag limit (a member of `TabBudget`, but will not be turned off to make
    room).
  - **One page, two hours**: At most one page globally, keep the new one and turn off the old one
    (reason replaced); automatically turn off after `KEEP_OPEN_HOURS` (2 hours) (check once per
    minute according to the wall clock, and turn off after the system wakes up from deep sleep);
    also turn off when the system requires memory release (`TRIM_MEMORY_RUNNING_CRITICAL` and
    above), the rendering process crashes or is taken back by the system (`WebTab.reclaimed`), or
    when the user turns it off. When closing, the cookie is written to the disk, the label is
    destroyed, the display is released, and the reason is recorded in the round of the source
    dialogue (`Turn.keptPageClosed`, `KeptPageClose`); when the dialogue is written in the round,
    which site is left (`Turn.keptPage`, `TaskService.recordTurn`, `PageKeeper.recordFor`, it is
    closed earlier than writing the dialogue and can be made up). Don’t start a new round, don’t
    invite models. The upper text of the next round indicates whether the page is still open or
    closed (`Conversation.context`; remember to keep it, and the page that Mina quit midway will be
    counted as closed).
  - **Process**: The task service does not exit when the page is left. When there is no task
    running, the foreground notification is replaced by its resident notification ("Background web
    page is running" · Site · Countdown to automatic shutdown, button "Close web page" "Open
    conversation", progress channel); when there is a task running, it is another ordinary resident
    notification. When the page is closed and there are no tasks, it exits the foreground and stops
    the service; when the service is destroyed, it faithfully closes the page (Mina exits).
  - **Interface**: On the dialogue page, there is a green line under the round of leaving the page
    "This page keeps running in the background · Site · How long it will be left before
    automatically closing · Close". When it is closed, it is replaced with a gray text "The
    background web page has been closed (reason)"; when there is no task running, the top column
    says "1 web page is maintained in the background" (`app/ui/KeptPageRow.kt`, `KeptPageCopy.kt`).
    Suspension Capsule: When there are no tasks and the results have been stopped, it expands to
    "Background web page is running" (green dot); when the switch is "Never Show", it will still not
    be displayed and only rely on notifications. Click to open its card (`CardView.Kept`): real-time
    picture (the latest frame it displays, the same source as picture-in-picture), how long it is
    left, which conversation it comes from, "open conversation" and "close web page".
- Apps on the virtual screen at the end of the task: The virtual screen on this side of the phone
  (`AgentDisplay`) is released with `ResourceScope` at the end of the task (`AgentDisplay.release` →
  built-in assistant `releaseDisplay` → `VirtualDisplay.release`); this screen was built with the
  "Destroy content when removed" sign, and the App window above it ends with the screen (whether the
  process is left or not is determined by the system), and the mute is restored as it is. The left
  page only cares about the web page, regardless of the apps on the screen.
- The rhythm of reading page by page (`core/cap/web/PagePacing.kt` for pure logic, `PageDwell.kt`
  for receiving tasks): In addition to the mouse and keyboard rhythm of each action itself, the same
  website (according to the registrable domain name) does not open in seconds even after page
  changes. When reading the screen or opening a web page, the address changes (not counting `#`),
  and when scrolling down, the page itself receives batches of JSON or text responses (loading
  more), all of which count as one page; this website does not stay on the first page in the last 10
  minutes, but stays on the second page for 4-7 seconds. After that, each additional page adds 0.6 /
  1 second to the upper and lower limits, up to a maximum of 12 seconds. It may be necessary to make
  up for the unfinished part of the page before the page change action (open the same site URL,
  click, back, press the button, scroll down, slide, `web_interact` button), model thinking, and the
  time spent reading the page are included; typing, clearing, scrolling up, reading, etc., or going
  to other websites. How long it has been stopped is recorded in this call (`ToolExecution.waitMs` →
  run record `tool_result.waitMs`, which is counted as waiting in the summary, and `do_steps`
  sub-steps are also counted in that batch), and the model is not told separately; it is only
  installed by the App task (`toolDispatcher(webPacing = …)`), and the core test defaults to
  different values.
  - Site knowledge's `pace: careful` (this website limits browsing speed, see
    `docs/dev/knowledge-format.md`) makes the pages of this website pause longer: 10-16 seconds from
    the second page, and the upper and lower limits add 0.6 / 1 second for each additional page, up
    to a maximum of 30 seconds. Check the site knowledge according to the current host name
    (`GuideCatalog.sitePace`, the rules for identifying the site and injecting the site knowledge
    are the same `SiteMatch`), and pass it to `PageDwell` through `toolDispatcher(webSitePace = …)`.
  - There is an upper limit for pages from the same website in a task (normally 20 pages,`careful`12
    pages; any time after the 10-minute window will be counted). After arriving, the page change
    actions (opening the same site URL, clicking, backing, and pressing keys) are not performed, and
    the report`page_limit`, in the description, ask the model to get the answers that have been read
    first, and the user can use them if they want to continue reading.`ask_user`Q; Scroll down and
    read the page as usual. The user has answered the question (answered the follow-up question,
    handed over the takeover, and has`Capability.onUserAnswered`) after each website is re-counted -
    no additional parameters are given to the model to increase the upper limit. If you want to see
    more, you can only get the user's nod. The rejected call will be recorded in the running record
    as usual.`tool_result`). The upper limit only covers browsing and does not interrupt batch
    connections and deliveries: external actions (send, submit) recognised by irreversible
    judgments are not blocked by the upper limit (`error: page_limit` is returned before
    `ActionExecutor` acts; the web capability is told and completes the experience through
    `Capability.onOutwardStart`). After one such action completes, this website (by registrable
    domain name) has no upper limit for the rest of this task, and user responses do not restore
    it. The website reports the limit by itself in batches (`onOutwardEnd`), on risk control, or
    when the user stops; the interval between stays and external actions stays the same. With no
    pacing (`PagePacing.Off`) there is no upper limit either.
  - Small actions in the stay (`core/uistate/IdleScroll.kt` is pure logic, it is also used for stays
    in the App): When the stay is at least 3 seconds, and the action is not based on the screen
    coordinates (press numbered point, back, button, scroll down, open the URL), gently scroll 1–3
    times to scroll down the entire page, 0.06–0.16 viewports each time, mostly down, and
    occasionally go back a little (not more than the previous scroll), and the total number of times
    does not exceed 0.45 viewports, net movement does not exceed 0.3 viewports; the total dwell time
    remains unchanged. Press the coordinate point and the slide will not scroll (the coordinates
    will not align). Ported `WebPort.idleScroll`: The desktop version uses the scroll wheel and the
    pointer does not move, otherwise it will slide slowly and natively; it does not move the mouse,
    does not hover, and does not click; it does not scroll when there is a page pop-up box, the
    input method is on, the input box has the focus, and the label is not displayed. After scrolling
    a few times, it is only recorded in the running record (`idleScrolls` in the `tool_result`
    result) without telling the model; after scrolling, taking another look before the action is
    regarded as "before action" (`ScreenSession.reprobe`). This scrolling is not considered the
    effect of the action, and the screen in the model's hand is marked as outdated
    (`interfaceStale`); the next time you read the screen, you will see the actual position after
    scrolling. It is only installed by the App task (`toolDispatcher(webIdleScroll = …)`), and the
    core does not roll by default.
- Risk control: Web page obstacle determination (`WebBarrier`), in addition to the current limit
  status code and human-machine verification framework, also involves poorly literate risk control
  interception pages (abnormal access, abnormal environment, too frequent operations, unusual
  traffic, or titles such as "security prompt" and "Security check" plus words such as exception and
  verification), which are judged to require user processing (access_blocked). `web_diagnose`
  prompts to hand it over to the user, do not try again immediately; `web_capture` When the
  retrieved response top-level report fields (message, msg, error...) are blocked by risk control,
  the result is `riskControl` and the same prompt.
- Tools: `web_navigate`, `web_observe`, `web_read_page`, `web_list_elements`, `web_tap`,
  `web_type_text`, `web_clear_text`, `web_scroll`, `web_swipe`, `web_press_key`, `web_back`,
  `web_tabs`, `web_wait_for`, `web_interact`, `web_downloads`, `web_upload`, `web_capture`,
  `web_screenshot`, `web_diagnose`, `web_evaluate_js`, `web_script`, `web_site_mode`,
  `web_keep_open`, and image viewing tools.

### sandbox:Linux sandbox

- Based on the operating environment of PRoot and Alpine 3.22, it comes with the APK. The App is
  unpacked offline in the background when it starts (`app/cap/sandbox/RuntimePreparer.kt`,
  `RootfsInstaller.kt`); Mina automatically re-unpacks after upgrade. The sandbox program only has
  the arm64 version. Other devices display "This device does not support it (requires arm64)", and
  the model is also told not to use this set of tools. See `tools/sandbox/README.md` for sources and
  construction.
- Each conversation has a workspace, and the sandbox is `/workspace`, which is retained across
  messages; files attached in the chat are put into `/workspace/inbox/`; `share_file`. The files are
  saved to the "Download/Mina" of the phone and displayed as file cards in the conversation; when
  the card is clicked, web pages, Markdown, PDF, images and text files are opened on the viewing
  page in the App (`FileViewerActivity`, see Section 15), and the rest are left to the App that can
  open it in the system.
- The network is disconnected by default, and "Allow scripts to connect to the Internet" in Settings
  → Advanced → File and Computing Environment must be turned on before connecting to the Internet.
  When the device cannot isolate the network, it can only run if "Allow scripts to connect to the
  Internet" is turned on (`AndroidSandbox`, `AdvancedSection` prompts). Each task has a cumulative
  running time budget, and the same command will be stopped if it fails repeatedly
  (`core/cap/sandbox/SandboxGuard.kt`).
- **Network Proxy**: Use the "Network Proxy" setting on the home page (see Section 12 "Network
  Proxy"). That line on this page is only displayed and is not changed here. Only give it in the
  command that allows networking (`AndroidSandbox` is empty when not connected to the Internet), and
  put `http_proxy`/`https_proxy`/`all_proxy` (one copy each in upper and lower case) and `no_proxy`
  (`LocalHosts.NO_PROXY`: local machine, LAN segment and suffix direct connection) through
  `ProcRunner.env`; the `https://` agent only uses the commands here.
- **Used space**: A group below the same page (`SandboxUsageReader`,
  `app/cap/sandbox/SandboxUsage.kt`): total, operating environment, skills, and conversation
  workspaces, and then list the workspaces by conversation (by conversation title, if the
  conversation has been deleted, write "Deleted Conversation", the largest one first). The size is
  calculated as an ordinary file in the directory tree, and the link is not followed
  (`AndroidSandbox.treeBytes`). Click a line to confirm before clearing that workspace, and the last
  line clears everything; just like deleting a conversation, delete it by handle through the file
  gate, do not follow the link, hold the sharing permission of the tree lock, and reset the mutex;
  dialogues with tasks running or queued indicate "cannot be cleaned temporarily", skip them when
  clearing everything. The operating environment and skills come with the App and are not cleared
  here (you need to prepare from scratch using "Reset Operating Environment"). There is no mobile
  phone storage in the container.
- Tools: `run_command`, `run_bundled_script` (run the scripts that come with knowledge and skills,
  see `docs/dev/skill-scripts.md`), `read_file`, `write_file`, `search_files`, `share_file`.
- A command in the sandbox can drive the mobile tools of the current task with
  `mina phone <operation> '<json args>'` (`app/cap/sandbox/SandboxBridge.kt`,
  `BridgeProtocol.kt`): each command opens a local socket with a random name and a one-time token,
  and the tools themselves declare which ones are callable (`ToolInfo.sandboxCallable`: tools with
  mobile capability are callable by default, image-viewing ones are not; adding mobile tools needs
  no list change). `<operation>` is the tool name without `phone_` (`type`, `clear`, `key`,
  `launch`, `dump` stand for `phone_type_text`, `phone_clear_text`, `phone_press_key`,
  `phone_launch_app`, `phone_list_elements`). Each operation still goes through the single entrance
  through `SubActions` and is written into the running record. The timeout and upper limit are the
  same as direct calls (up to 30 seconds for `phone_wait` / `phone_event`, 10 seconds for
  `phone_system_command`). There are also `mina share` and `mina progress`.

### Look at the picture

If a model that can see images is configured (§12), `phone_describe` (describe the screen) and
`web_describe` are provided. Visual locating (`phone_find_visually`, `web_find_visually`) first
searches the control tree precisely; if the target is not found, it marks the frame and checks the
name. A model whose locating accuracy has not been verified must provide supporting evidence — the
tree of this screen cannot be used, or a part is missing and the thing being looked for is not in
the tree — and if there is nothing to compare against, the frame is still handed over and the
result is confirmed by the effect after the click. Describing the screen reads the whole screen and
writes a long paragraph, several times slower than locating: the prompt only allows it when the
model cannot tell what page it is, and the description requirements (`roles/describe.md`) ask for
only four short parts — at most 15 lines of interactive elements, a few words each, no text that is
not a control, only the first few items of a long list, and at most two lines of clues. The
screenshot tool is provided only when the main model can see images. Graphical CAPTCHAs are not
solved by Mina; they are left to the user.

### subtask

`spawn_agent` (`core/general/SpawnAgent.kt`, `core/child/ChildRuns.kt`) sends a subtask to check a
bounded problem: only web page and sandbox, read-only, no one present, default 15 steps (model can
be transferred to `maxSteps`, 1–40), cannot ask questions, cannot send externally, cannot write
memory, cannot build timing, cannot send subtasks, and there is no knowledge and skill query tool.
The result has an upper limit on length (default is 4,000 words, the model can pass
`maxResultChars`, 500–8,000; if it exceeds the pull-in range, if it is not an integer, it will be
returned by parameter error; when truncated, the result will be written as `truncated`), and it will
be returned to the parent task as an untrustworthy observation; only one is sent at a time, and the
parent task will wait for it to be completed; the completion of the sub-task does not mean the
completion of the user's goal. It is only provided when the dialog interface is initiated and has
web page capabilities.

### Records from previous rounds

The upper part of the dialogue only contains the endings of the most recent rounds
(`Conversation.context`: up to 5 rounds, 2,500 tokens), and the original text read in the previous
rounds is not included. `history_read` (`core/general/HistoryRead.kt`) allows the model to check by
itself: without `turn`, list the previous rounds (the new one first, up to 20 rounds: which round,
user's original words, ending, starting time, and whether there are detailed records); with `turn`
(1 = the round that just passed), read the complete ending of that round, if the user interrupted
midway, Mina The progress description (with the words in the call reply) and the original text read
(`observed` in the running record, the same original text is only given once), paging by article
(`offset` / `nextOffset`, one page is about 8,000 words, one article is up to 2,000 words), without
screenshots. Only the rounds of the same conversation (`Turn.runId` in `RunSpec.priorTurns`) brought
about by the start of this run are recognized. Other conversations and other runs cannot be found;
read-only. The original text is taken from the running record (`core/record/RunRecords.kt`, the app
side is `RunStore.reader()`, read on the IO thread). It has been desensitized and truncated when it
is placed on the disk, and is read as it is. Untrustworthy web pages are still included in
`<untrusted>`. The original text read was submitted for verification as an observation (the source
is recorded as `history`), and the use of the previously read value in the answer is well
documented. Only the last 30 times are kept in the running record, and only the ending
(`recorded=false`) of the earlier round is left. It will only be provided if there are running
records and previous rounds of running. In this case, there will be an additional hint at the end of
the above article (history_hint in `feedback/loop.md`). Subtasks do not have previous rounds and are
not provided. It can also be adjusted when discussing history (the intention is judged as
discussion).

### Computer connection: CLI and MCP

Both directions. **Mina is used as an MCP server** (computer connection) has been connected, there
are two connection methods: via USB (open with adb on the computer, only listen to loopback) and via
LAN (enabled by the user in the settings, off by default); **Mina is used as an MCP client** to use
other servers (`core/cap/mcp/McpClient.kt`, according to the annotation to determine risks, tool
list startup freeze) and low-level tool sessions (`core/mcp/McpServer.kt`) only have skeletons and
tests. For user documentation, see [mcp.md](../mcp.md) and [cli.md](../cli.md). The computer CLI
uses the common HTTP task API, parallel to the MCP adapter, and shares a task service.

The layers of **server** are all in `:core`, and you can test the entire set on your computer:

- `MiniHttpServer`(`core/remote/HttpServer.kt`): A shared minimalist HTTP/1.1 listener. There is one
  thread for each connection, and there are upper limits for the number of connections, request
  headers, and bodies. With Content-Length and chunked, connections can be reused. The path and
  query parameters are reserved for entrance verification; TLS is not used, and only private
  addresses and access tokens are collected for verification.
- `RemoteAccess` (`core/remote/RemoteAccess.kt`): the gate shared by both entrances, in a fixed
  order: 403 when the `Origin` header is present; an address that fails authentication 5 times
  within one minute is blocked for one minute (429 with `Retry-After`); `Authorization: Bearer`
  tokens are compared in constant time and a mismatch is 401. Routing and error formats are decided
  by the adapter, and unknown paths pass authentication first. `privateAddress` recognises loopback,
  LAN, private-network and link-local addresses; if either end of the connection is not a private
  address it is disconnected on the spot. The LAN token accepts all allowed connections, the USB
  token accepts only loopback; both are compared, with no short-circuit. Authentication failure
  counts are shared between the API and MCP.
- `RemoteTaskService` (`core/remote/RemoteTaskService.kt`): Protocol-independent start, query, list,
  wait, answer and cancellation use cases, centralized verification of input and task status, return
  typed results and business errors; does not include HTTP, MCP tool names or JSON-RPC envelopes.
  `RemoteTasks` / `RemoteTask` / `RemoteStatus` in `core/remote/RemoteTasks.kt`; the status is only
  mapped from the existing `TaskState`, and the two entries share fields and the same task index.
  Wait for return according to status changes (changes in the progress description are not counted),
  up to 50 seconds; if the task in the index disappears, it will be reported as non-existent. The
  mcp- prefix is retained for compatibility with existing remote conversation IDs and is isolated
  from the local conversation on the phone.
- `RemoteTaskApi` (`core/remote/RemoteTaskApi.kt`): Common HTTP/JSON entry `/api/v1`, providing
  version, task life cycle and read-only device status without MCP handshake or tool discovery.
  Business errors are mapped to HTTP status and code/message; unknown fields are rejected. For
  detailed contracts, see [CLI document](../cli.md#http-contract).
- `McpRpc` (`core/mcp/McpRpc.kt`): JSON-RPC on Streamable HTTP, only returns a single JSON, does not
  open the SSE stream (allowed by the specification), GET / DELETE returns 405, does not send a
  session number, and the server does not send a request. Both generations of protocols recognize:
  each request of the new generation (2026-07-28) carries the version and client capabilities in
  `_meta`. The `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` in the HTTP header must be
  consistent with the text (inconsistency -32020). If the version is not recognized, it will return
  -32022 and the recognized version will be listed side by side. Support `server/discover`; The
  older generation (2025-03-26 to 2025-11-25) shakes hands with `initialize` first and chooses the
  version recognized by both parties. If the tool parameters are incorrect or the task cannot be
  found, it means a tool execution error (`isError`). Return it to the opposite model for viewing;
  if the tool name is not recognized, it means a protocol error.
- `McpTaskTools` (`core/mcp/McpTaskTools.kt`): Retain the original tool name, schema, MCP prompt
  copy and return format, and convert the parameters to a shared service call. `mina_run_task` /
  `mina_task_status` / `mina_wait_task` / `mina_answer` / `mina_cancel_task` / `mina_list_tasks` do
  not save the task status. Payment, confirmation and mobile phone takeover cannot be released with
  normal answers.
- `RemoteEndpoints` (`core/remote/RemoteEndpoints.kt`): Common protocol routing after completing
  transmission authentication, holding `RemoteTaskService`, handing `/api/v1` to the ordinary API,
  `/mcp` to the MCP, and recording recent activities. It does not authenticate the connection, and
  the caller must first complete the authentication of the corresponding entrance.
- `RemoteHost` (`core/remote/RemoteHost.kt`): LAN/USB entrance, holds `RemoteAccess` and HTTP
  monitoring, and delivers it to the shared router after authentication. `bindAddress` selects all
  network cards or IPv4 loopback 127.0.0.1, consistent with ADB forwarding. Private address checking
  is not relaxed for remote gateways.
- `RelayGateway` / `RelayClient` (`core/remote/`): HTTPS paired with active WSS channel, does not
  follow redirects, only forwards fixed MCP POST. Requests have independent numbers, bounded
  concurrency and text, repeated numbers are not executed, cancellation and disconnection are
  interrupted and waited; the connection is backed off and reestablished but the operation is not
  replayed. The user authorization that has been verified by the gateway is not transparently
  transmitted as a USB token and does not forge the loopback source. The tunnel shares protocol
  routing with the local ingress, and tasks are still processed from the existing remote origin.
- `McpUsb` (`core/mcp/McpUsb.kt`): USB agreement with the computer. Connection information
  `mcp-usb.json` adds the api_url / api_version of the ordinary API, and retains the original
  content (Streamable HTTP address, `Authorization` header, token, port, `adb forward` command, the
  number of this boot `boot`); "connect-computer.sh" running on the computer: If it is not a shell
  (uid 2000), it will not run. Run it first. "usb-start.sh" starts the mobile assistant, then `am
  start-foreground-service` starts `McpService` (action `USB_ON`, with `off` parameter it is
  `USB_OFF`), wait until the matching connection information between `boot` and `settings get global
  boot_count` is written (recognize that it is not left from the last boot), type it out; wait for
  the file to be deleted when it is turned off. The script carries no secrets.

**App side**:

- Self-hosted remote mode: `RelaySettings` (`settings/RelaySettings.kt`) The entire encrypted atom
  saves the pairing and switch, independent Keystore key; the state stream only contains non-secret
  fields. `McpRuntime` manages this channel independently from the original monitoring, and the
  `McpService` front-end service is used when the remote is enabled. The closing action of the
  notification also closes remote access. Pairing itself does not enable access, the pairing is
  retained after closing, and unpairing is closed first and then revoked by the gateway.
  `RelaySection` is the "remote" item in the MCP boot, using existing components and
  Chinese-English-Traditional resources, and does not display saved tokens.

- The kind of LAN: the switch and port exist `files/settings/mcp.json`, the access token (32-byte
  random number) and the service provider's Key are encrypted by `KeyCipher` and exist
  `mcp-token.bin` (`settings/McpSettings.kt`), the default is off.
- USB kind (`settings/McpUsbSettings.kt`): Another switch, another token, exists
  `files/settings/mcp-usb.json` (along with the boot number when turned on
  `Settings.Global.BOOT_COUNT`). A token is generated when it is opened, and is used when it is
  opened again; after listening, the connection information is written to `mcp-usb.json` in the
  external file directory (from Android 11 onwards, other apps cannot read it, but the shell can;
  the token is originally given to the computer in clear text, so the clear text is also stored in
  the private directory); it is deleted before it is opened. Forget the token when shutting down
  (replace it with a new one next time) and delete two files. No cross-reboot: If the boot number
  changes, just press Off and clear the last file.
- `McpModes`(`app/mcp/McpModes.kt`): How to combine the two switches. Only one port is open: LAN is
  open and all network cards are connected (including loopback, USB can still be connected), only
  USB is open and loopback is only connected, and it will not listen to all the others; the action
  received by the service is `USB_ON` / `USB_OFF` / `TURN_OFF` ("off" on the notification, both are
  off) which switch should be changed.
- `McpRuntime` (`app/mcp/McpRuntime.kt`) A copy of the entire process: `McpService`
  (`app/mcp/McpService.kt`, specialUse front-end service, resident with a low-priority notification,
  which can be turned off directly), the service will only monitor when it is turned on; `sync` will
  be executed every time the service is started: If the monitoring method changes (the LAN is turned
  on or off), change the monitor, USB If it is turned on, the connection information will be
  written; if it is turned off, the service will be stopped and the port will be closed. Write
  "connect-computer.sh" once when the app starts.
- `McpService` is open to the outside world and requires the caller to hold
  `INTERACT_ACROSS_USERS_FULL`: shell. It cannot be obtained by ordinary apps, nor can adb (the same
  door as the provider used by the assistant), so `adb shell am start-foreground-service -n
  <package>/.mcp.McpService -a com.meenai.mina.mcp.USB_ON` can only be issued by the computer through USB
  debugging. Use foreground services instead of broadcasting: Starting from Android 12, background
  apps generally cannot open foreground services by themselves, and the foreground service initiated
  by the shell is allowed according to the system uid; when entering the foreground is rejected, the
  service exits without crashing. The list test (`ManifestProvidersTest`) guards this authority. The
  task service will exit by itself when there are no tasks, so the monitoring is not placed in
  `TaskService`. The service will be rebuilt when it is recycled by the system (START_STICKY); when
  booting or opening the Mina interface, if the switch is on but not running, it will be repaired.
- `RemoteTaskHub` (`app/task/RemoteTaskHub.kt`) implements `RemoteTasks`: `TaskService.startMcp`
  starts a task along the same path as the chat UI, and both CLI and MCP use the permission policy
  of the existing external task origin `RunOrigin.Mcp` (a historical name here; it does not mean
  CLI goes through the MCP protocol) and presence `Reachable` (follow-up questions wait as usual
  and count as unanswered at the deadline; a step handed to the user has the same deadline, and
  when nobody handles it in time the task stops). If the same conversation already has a task, the
  new one queues behind it and is not lost. Status is read from the task itself (`TaskRun.state`);
  this hub only records which conversation each computer-supplied number belongs to, the original
  words, and the final state at the end (including being cancelled while queued or the service
  being stopped); a task handed over that is not registered within two minutes counts as not
  started. An answer goes straight to `TaskRun.answer`, only while the task is waiting for a
  follow-up answer; stop goes through `TaskService`'s `ACTION_CANCEL_MCP` (a queued task is removed
  directly). Only numbers registered here are recognised; tasks started on the phone cannot be seen
  or touched by the computer. New conversations still ask the model for a title as usual.
- Several security guarantees: payment and confirmation can never be released from the computer -
  `mina_answer` only gives the answer to the task waiting for questioning; `request_confirmation`
  directly rejects the payment initiated by the non-mobile phone (`declined_remote`),
  `ApprovalPolicy` will deny the payment initiated by the non-mobile phone; the operation initiated
  by CLI or MCP will start by asking regardless of the autonomous switch
  (`ApprovalControl.start(remote = true)`), so the autonomous mode cannot allow payment.
  Notifications for asking questions and taking over popped up on the phone as usual. The start
  event of the running record indicates the source (`RunEvent.TaskStarted.origin` = `mcp`, if it is
  initiated on the mobile phone, do not write it), which is also included in the summary.
- Settings page (`ui/McpScreen.kt`): The top section distinguishes between access not enabled,
  waiting for computer request, recently received request and startup failure; turning on monitoring
  or switching does not mean that the computer is connected. First select CLI / Skill or MCP, and
  then select the USB or Wi-Fi / private network mode shared by both. CLI only has two steps:
  installation and connection. Connect comes with connection verification; the network method
  provides HTTP root address, token file command and token copy, and MCP provides /mcp
  configuration. Switching boot does not change permissions. Access switches, token management and
  remote wake-up are placed in the advanced settings that are folded by default; the network MCP
  configuration preview is also folded by default and the token is hidden, and the complete
  configuration is generated only when copied. The address and request time will be refreshed within
  two seconds of the page. `ComputerAccessScreenTest` Verifies boot switching, explicit permission
  operations, status and configuration replication.

`mina_steer_task` and `POST /api/v1/tasks/ID/steer` (CLI `task steer`) are additionally requested
through the same `RemoteTaskService.steer`. Only `running` remote tasks are accepted;
`TaskRun.steerRemote` rechecks running, pause/wait and cancellation status, `RunInbox.steerCurrent`
and shutdown/cancel are mutually exclusive. Messages only belong to the current task. When they are
finished without being consumed, they are marked as unused. UI interruption is not used for the next
task. Return message number and inbox receipt confirmation; `steers` of task status only lists
`received`/`joined`/`not_applied` of remote messages, without mobile phone interruption or message
body, and the final status retains a snapshot.

The ordinary API only provides read-only device status for use by CLI doctor; MCP also provides home
screen preparation tools `mina_device_state`, `mina_wake_device`, and `mina_unlock_device`. They
belong to the host device management (same as pairing and recovery), do not register as a model's
`AgentTool`, and do not enter the model context, action history, or credential logs. After MCP
original authentication, it is adjusted by `McpDeviceTools` to `core/device/DeviceAccess.kt`: Home
screen status → Wake up → Request to display the credential page → Enter local credentials if
necessary → Verify system status. The core only relies on `DeviceAccessPort` and
`DeviceAccessStore`; Android is adapted in `app/device/`, the main screen input shell is implemented
in `cap/phone/shell/DeviceUnlockInput.kt`, AIDL has a separate method, and the ordinary virtual
screen button whitelist remains unchanged.

`DeviceAccess` One copy per process, Mutex serialization; remote permission is off by default, and
credentials can only be set on the mobile phone. `DeviceAccessSettings` atomically writes the entire
configuration encrypted with a separate Keystore key, caching only the non-secret state. Persistence
failure space before input will be cleared only when the system is unlocked; credentials must be
re-saved locally after failure/interruption. Android adaptation only enters the unique,
type-matching password box on the main screen SystemUI, and does not process SIM PIN or
unrecognizable manufacturer pages; the keystrokes are directed through `ShellService.injectTo`
injection, and are only cast to the focus window owned by SystemUI (`injectInputEvent(event, mode,
targetUid)` starting from Android 13). When there is a window above the lock screen or it is
unlocked and returned to the App, the system rejects it. The keys will not fall elsewhere, and the
modifier keys are only released to SystemUI after the keys are sent; Android 13 No credentials were
previously entered (`unsupported_keyguard`). `TaskGate` remains unchanged, and the system will
automatically continue after the screen is actually turned on and unlocked. The settings UI is a
group card of the computer connection page. It uses existing row/input/button components and Chinese
and English resources. The UI state can be restored if the credentials are not entered, and
screenshots are prohibited during editing. This feature does not change task sources and payment
policies.

## 7. Context and compression

- **Cache Partition** (`core/agent/ContextBuilder.kt`, `ContextCompressor.kt`): Area 0 is the system
  prompt word and tool definition. Within a task, it only changes when a group of tools is expanded
  (each group is at most once, and only the part starting from that group is changed). The rest of
  the time remains unchanged (only the tools that have been abandoned are given in the last round
  when the number of steps is used up, and tools are not given when it is judged as a repeat
  question during discussion, except for these two requests); Area 1 It is the starting context,
  written once and never changed (previous conversations, memories and portraits, original words of
  the task, available channels, App/website knowledge, knowledge and skill index; all except the
  user's original words are included in `<context kind=… source=… trust=…>`); Area 2 is the history,
  only appended; Area 3 is the result, screen and runtime status of this step (which interface,
  which step, local time, and the beginning of the word the user typed in the most recent sentence
  are attached to the end of the last message). The text written to the model by the main loop (the
  source and credibility of the shell, runtime status, reminders, instructions for confirmation and
  acceptance, playback process, tool results, folding summary) are all in English, consistent with
  the system prompt words and tool descriptions, to prevent the model from answering in Chinese; the
  text shown to the user is still in the interface language.
- **Append only**: Normally sent messages are not rewritten, and the complete history is never
  modified; only when compressed, they are rewritten once on the copy. `AnthropicModel` When the
  assistant message is returned, the order of the blocks is as received, and the thinking blocks do
  not move. Models starting from Claude 5.1 will also verify the returned thinking block: the
  signature and the system prompt word when it was generated, the tool, the previous message
  binding, a group of tools expanded, and after compression (as well as the last round when the
  number of steps has been used up, and the re-question when the tool was not given during the
  discussion) will not match, and some accounts and models will directly return 400. When rejected,
  `AnthropicModel` removes all the thinking blocks that were brought this time and sends them again.
  Subsequent requests for the same model instance (one for each task, shared by subtasks) will no
  longer carry them (they will remain in the history), and the received ones after resending will be
  returned as usual. Check zone 0 by online bytes and only append `PrefixStabilityTest` (compatible
  interface for OpenAI) and `PrefixStabilityAdaptersTest` (compatible interface for Responses,
  Anthropic, Gemini and standard cache), running the same 30-step fake task: zone 0 only changes
  when expanding, and history is only rewritten when compressing; the latter also checks the
  playback of encrypted thoughts, thought signatures and thoughtSignature at every step, Anthropic
  No more than 4 cache tags may be requested at one time.
- **Token budget**: Starting context, memory, previous rounds, App/website knowledge are all
  calculated by token (`TokenEstimate` rough estimate): total 8,000, Task 4,000, Knowledge 5,000,
  single text 2,000; full memory 4,000, retrieval 800; previous rounds 2,500 (if there is only one
  round left and it is too long, the original story and the ending will be cut off separately); the
  previous rounds with ending judgment and intention judgment are 800. After calculating by token,
  each language accounts for the same amount of context: Compared with the original quota by word
  count, English can hold about twice as many words, and Chinese can hold about one-third less; the
  single copy of the built-in App/website knowledge text is within 2,000. The line used to calculate
  the "large result" during compression is 1,000 tokens (the number of tokens corresponding to the
  original 4,000 characters, the cutting method for English and JSON results remains unchanged). The
  upper limits of the tools and interfaces themselves (`maxChars`, `OutputClip`, web page reading
  and script results, summary fields, references, skill documents, etc.) are still character-based
  and have not changed.
- **When to compress**: Always compare the **whole request** (system prompt word + tool definition +
  message, the same caliber as the input token reported by the service provider). The size of the
  entire request is calculated by `UsageMeter`: when there is a recent reply with real usage of the
  same model, = the input token of that time (including the prompt word and tool definition at that
  time) + the output of this reply minus the thinking token reported separately by the service
  provider (`Usage.reasoningTokens`: compatible interfaces
  `completion_tokens_details.reasoning_tokens`, Responses `output_tokens_details.reasoning_tokens`,
  Gemini `thoughtsTokenCount`; Anthropic Not reported separately, calculated as all output) +
  estimation of subsequent messages + changes in fixed overhead since that time; when there is no
  anchor point (the first step, the model has been changed, and it has just been compressed), the
  message and fixed overhead are fully estimated. Fixed overhead is only counted this time.
  Estimates are always multiplied by the same "real/rough estimate" ratio: after smoothing the
  calibration with the real dosage, the more conservative 1.2 was used before. The trigger point is
  only calculated at `ContextLimits.of`: available = window − output reserve (up to 20K, small
  window is based on one quarter of the window); trigger point = available − margin (up to 13K,
  small window is based on one quarter of the available), not less than half of the window; one
  compression is below "half of the available, and one margin lower than the trigger point".
  Example: 32K window trigger point is 18K, 128K is 95K, 1M is 967K. After compression, at least a
  margin will be added before compressing again (hysteresis), but it will not exceed the available
  window; therefore, the history that has a large fixed overhead and cannot be compressed will not
  be compressed every step, and it will be compressed every step when it reaches the edge of the
  available window. Window and Absolute Trigger Click Vendor and Model Save in Settings, assembled
  by `ProviderStore.modelFor`. Unspecified trigger points are automatically calculated by
  `ContextLimits.of`, and manually filled values follow the same range. When the service provider
  reports that the context is too long, it will resend using a more aggressive gear. The context
  length of the selected model is inherited when the window is left empty.
- **How to compress** (without adjusting the model): First shorten the old results before the last
  12 rounds (old interface reading is replaced with placeholders, web pages, commands, and file
  contents are cut off if they are too long; observation numbers are retained in the placeholders),
  then fold the old rounds into "Earlier steps (compressed)"; if it is not enough, tighten it step
  by step. Structured summary (`StepSummary`) is implemented and is turned off by default.
- **Never cut**: What the user says (original words of the task, interruptions, answers) is never
  cut; the rounds of questioning, confirmation, and acceptance are not folded. After compression,
  `ContextRecovery` fills in the missing user information and start-up context (previous
  conversations, memories, knowledge, skills, channels).

## 8. Parallelism

Tasks of different conversations can be carried out at the same time, and each task has a virtual
screen (`app/task/ParallelCapability.kt`): Android 14 and above are required, and the speed switch
"Different conversations at the same time" is turned on (default off), the upper limit is 1–3
(default 2); only 1 is allowed for low-memory devices or when the second screen fails to be built.
What is truly exclusive is the same App, home screen takeover and clipboard, which are coordinated
by `ResourceScheduler` (`app/task/ResourceScheduler.kt`): the same App only has one task at the same
time, takeover is queued, the clipboard is locked, and the web page tab has a total budget.

**Wait for other tasks to release the App**: When you open an App that is being used by another
task, the result is `app_in_use_by_other_task`. The instructions tell the model to do other things
first, or bring `wait` (seconds, 1–180, `LaunchApp.MAX_WAIT_S`) and adjust `phone_launch_app` again.
Whether to wait and how long to wait are determined by the model according to the task, and there is
no set switch. When `wait` is brought and occupied again, the tool passes through
`ScreenPort.awaitAppFree` and so on: `AndroidScreen` treats "it is not occupied by other screens and
is not occupied by other tasks in the occupation registration" as released, and hands it to
`ResourceScheduler.awaitApp` (passed by `TaskCenter.awaitApp`) - it registers "who is waiting for
which App", and when there is a task released App (`releaseOwner`, when the task ends), it wakes up
the waiting App on other screens on the spot. If you turn it off by yourself, there will be no
notification, and you will look at it again every second; if you let it go, you will turn it on
again, and it will still be occupied when the time limit expires and return to `app_wait_timed_out`.
Before starting to wait, search along the lines of "Whose app is the task that occupies this App is
waiting for?", go around and wait for each other (the same applies to two or three tasks in a
circle), wait on the spot, and return to `app_wait_would_deadlock` (checking and registration are in
the same lock. When two tasks start waiting at the same time, the one that comes later can see the
one that came first; the one that comes first will still wait, and the one that comes later will
wait. After finishing, put down the app in your hand, and the one that came first will be waiting).
During this period of waiting, the task is still `Running`, and the progress is
`RunProgress.WaitingForApp` (the conversation page, notifications, and capsules all say "Waiting for
another task to finish using an app"). It is not paused, nor does it count as "asking you to handle
it"; after waiting, put back the original sentence. This is two levels different from the queue of
tasks handed over to the service on the dialog page (when parallelism is turned off, tasks elsewhere
start first, and new tasks are queued behind it and start after the end, and the message says "Wait
until the previous task is completed before starting"). There are two levels: that is, the entire
task is queued before other tasks are started, and only one is run at a time, and there is no
competition for Apps; here is a task that is already running in parallel and is waiting for an App
in the middle.

## 9. Experience replay

Successful routes can be recorded, similar tasks can be played back directly, each step is lightly
checked, and the model is not adjusted. If it is not correct, the model will be returned
(`core/replay/`, the playback action is executed through the only entrance `Origin.Replay`). The
route is only recorded from the completed run (`LearnGate`, `TaskRun.settleOutcome` is taken from
the placed run event after the ending is judged, `RouteRecorder`, regardless of whether the route
itself is remembered); if the playback is completed and it is completed again this time, the route
success count is increased by one (`RouteReplay.noteResult`). The speed switch "does what we did
last time" is on by default: the old setting was upgraded once from `FeatureFlags`
(`speed_defaults_applied_v3`), and will remain off after the user turns it off.

## 10. Timing and conditional triggering

- **Tools**: `schedule_task`(`core/general/ScheduleTask.kt`),`op` =
  create(default)/list/pause/resume/delete/run_now/reschedule. Only available in runs initiated
  through the dialog interface.
- **Time rules** (`TriggerDefinition`, `core/trigger/Triggers.kt`): one-time (`at`); fixed interval
  (`intervalMinutes`, multiple misses are merged into one and the missed times are recorded);
  according to local time every day or day of the week (`repeatAt` + `repeatZone` + `weekdays`), the
  clock remains unchanged across daylight saving time.
- **Three methods**: Fixed reminder (`reminder`) is delivered directly to the point without
  adjusting the model; the service task is executed as an unattended Run at the point; with
  `condition`, the condition is first checked using a read-only web page and sandbox, and the result
  is structured as `ConditionOutcome` (`core/trigger/ConditionOutcome.kt`). Errors in the check are
  not considered "unsatisfied"; the action is only executed when the condition changes from
  unsatisfied to satisfied. `whenIdle` makes it wait until there are no tasks running.
- **Scheduling** (`app/task/TriggerRuntime.kt`): The system alarm is just a wake-up signal,
  `FileTriggerStore` is the real source. Before dispatching, write down the next time and this
  time's window (number = trigger + expiration time). The same window will not be dispatched twice.
  If you can use the accurate alarm clock, use it. If you don't have permission, a notification will
  be sent for the first time to guide the user to open the system settings. The result of
  `schedule_task` also tells the model that "it may not be on time"; a prompt is displayed on the
  planning page. Rearrange when booting, changing time, changing time zone, accurate alarm clock
  permission changes (`app/setup/BootRestoreReceiver.kt`), and also when the App is started.
- **Results returned to original dialog**: The task that reaches the point is run in the dialog that
  created it. The results, reminders and failure instructions are all written back to that dialog,
  and the notification click is it.
- **Killed in the middle of the process**: The reminder is re-delivered (removed by number); other
  claimed windows are marked as unknown results, and "Mina will not automatically repeat the
  operation" is explained and notified in the original dialogue.
- **Planning page** (`app/ui/PlansScreen.kt`): It is divided into two sections: "In progress" and
  "Disabled", each with three lines (what to do, repeat rule and next time or status, last result).
  The switch on the right is to pause and resume; click on a line to bring up the operation panel:
  view dialogue, execute immediately, change time, delete.

## 11. Knowledge and skills

- **Built in** (`core/src/main/resources/`): app knowledge (`knowledge/apps/<id>/APP.md`), site
  knowledge (`knowledge/sites/<domain>/SITE.md`), and skills (`skills/<id>/SKILL.md`), one per
  directory, so adding one means adding a directory. To see what exists, look at these directories
  or at the generated index `knowledge/index.json`; the list in the README is generated from the
  same index by `./gradlew updateReadme`. The format is described in
  [`knowledge-format.md`](knowledge-format.md). At build time `KnowledgeIndex` scans the headers,
  validates them and generates the index; a validation failure fails the build.
- **Automatic supply**: Match the task text at the beginning; supplement its knowledge text when
  entering an App (read its interface) or a website (successfully opened or read for the first
  time). Each copy is only given once, and each task has a maximum of 3 copies (`KnowledgeStore`,
  `NoticePolicy`).
- **Skills are given in two layers** (`GuideRecommender`, `ContextBuilder.start`): All skills are
  listed in the index at the beginning, and are not selected according to the task text - built-in
  skills are first, personal skills are last, each is arranged by name, each has one line (id,
  display name, description; the description in the trial is marked as not yet verified), disabled
  and pending drafts are not listed. The index is written once in the startup context. It will not
  be added or changed when switching to the front desk in the middle. It will be given in full and
  will not be cut by the budget. Whether to use it and which copy to use will be determined by the
  description of the model. When you want to use it, use `skill_read` to read the full text; no more
  full text of any skills will be inserted at the beginning. After reading a personal skill,
  `SkillUsed` is recorded once (`how` = viewed; injected in the old record is still read and counted
  as used), and the result of that skill is recorded accordingly in the mission outcome.
- **Check tool** (`core/general/LookupTools.kt`): `knowledge_search` / `knowledge_read` checks App
  and website knowledge, `skill_search` / `skill_read` checks skills (both built-in and personal).
- **Write skills according to user requirements** (`core/general/SkillSave.kt`, built-in skill
  `skills/skill-authoring`): When the user wants to create, import or rewrite a skill in a task, the
  model writes the entire document according to the built-in "Write Skill" skill, and saves it as a
  personal skill using `skill_save`. The citation must come from what the user said this time (words
  in pages and files cannot be written by others); it must pass the same verification process as the
  self-study draft (`SkillDrafter.validate`: name, description, five sections, length, tool name,
  sensitive content); the name of the built-in skill cannot be used, and the one with the same name
  as the draft to be confirmed will not be accepted; if you modify an existing copy, you must bring
  the version returned by `skill_read` (the first 12 digits of the original text fingerprint, if it
  does not match, it means it has been changed halfway). Save it and go directly to the trial, mark
  "Write according to your requirements" (`meta.json`'s `source: user`) in "Memory and Skills", and
  convert it to official once; change the name and delete it and leave it in the settings. This time
  the saved skills will no longer start a new self-study draft (`Eligibility`). What the user said
  this time (`UserUtterances`) no longer follows the memory switch: it is only in the memory, and
  each task starts to be cleared. When the memory is turned off, the memory_* tool is still not
  provided, and it is not used to extract the memory.
- **Personal skills self-study** (`core/skills/`, `app/task/RunLearning.kt`): After the task is
  completed (`LearnGate`, it is not required to pass the final verification), draft a personal skill
  according to the conditions (enough actions, no skipped takeover, the user did not complete it by
  himself, no disagreement confirmation, not replayed, see `Eligibility`); When drafting, assign
  existing personal skills (together with drafts to be confirmed) to the model one by one. Which of
  them is the same type of event this time? The model writes `updates: <name>`, the code only checks
  whether this copy exists: if it points to the one that has been accepted, it will be regarded as
  its updated draft (overwrite it when accepting, and check with the original text fingerprint), if
  it points to the draft to be confirmed, the draft will be replaced, and if the name does not
  match, it will be regarded as a new one (the new one cannot occupy the existing name); the draft
  must be accepted by the user in "Memory and Skills" before it can be tried. When the trial is
  successful, it will be converted to official, and if it fails, it will be deactivated; there are 2
  formal skills in the past 3 times. Disable it even if the result is bad (`SkillOutcomes`, if the
  outcome is not successful, it will not be counted). The switch "Learn skills from completed tasks"
  is on by default (the old setting is upgraded once when `MemorySettings` is read, and remains off
  after the user turns it off).
- **App knowledge self-study** (`core/knowledge/AppKnowledgeLearning.kt`,
  `app/knowledge/AppKnowledgeFiles.kt`): Under the same switch, the dialogue interface is initiated
  and the mobile app is run. Each app that has been used and has built-in knowledge entries will be
  judged once (apps without entries will not be supplemented and will not be learned). There are
  three types of things worth learning (`AppKnowledgeDrafter.kind`): it’s done, and there are enough
  actions that are effective in the App; it’s done, but it’s stuck (an error occurred or the
  interface didn’t change more than two times, or the app returned more than twice); Not completed
  or only partially completed (decided as Partial / NotPossible, or giving up, running out of
  steps), and having taken at least 3 steps in this App. Those that are canceled, those that skip
  takeover, those that are completed by the user themselves, and those that are played back are not
  learned. Please model once (lightweight models are preferred; role
  `prompts/roles/app_knowledge.md`, the requested format is `app_knowledge_input.md`; If a verified
  supplement is successfully completed, please do not ask), give it the built-in description of the
  app, the supplement in use, the ending and the simplified action list (with error codes, interface
  changes, without the original screen text and input words), and the full text after the supplement
  is returned: only the fact lines starting with "-", up to 12; if there are no worthy supplements,
  NONE will be returned, and nothing will be output. Give the goal, answer, or reason for failure in
  the request as it is; The returned supplement will be received as it is (the data is only on the
  mobile phone and will not be desensitized). Only the complete copy with passwords and verification
  codes will not be accepted (`SensitiveFilter`). Each App can have at most one revision to be
  confirmed. If the revision does not correspond to the one currently injected (the other one was
  accepted first), it will not be accepted; After the copy you are using has changed (accepted,
  archived, and changed the trial stage), archive the revisions to be confirmed on the other draft
  copy on the spot. Accepting the outdated revisions will only archive them, and will not always
  occupy the position to block future drafts of the app; accept the copy that replaced it during the
  trial period (the old one will not be injected or deleted), archive the old one when it becomes
  official, and replace it with the old one when it is archived (`AppKnowledgeTrial.inUse`).
  Attached to the App description when injecting, the trial mark indicates that it has not been used
  for completed tasks (`feedback/loop.md`’s `knowledge.learned_trial`, the verified one is
  `knowledge.learned`). The running results that have been used in the trial draft will be converted
  into official ones; those that have been learned from experience will be archived if they are not
  good twice in a row, and the other one will be archived (`AppKnowledgeTrial`); just like personal
  skills, if there is a problem with the model, the operating environment, or the operation
  protection is stopped, it is neither good nor bad (`SkillOutcomes.resultOf`). "To be confirmed"
  indicates whether it is a supplement or a revision, and lists the added and deleted lines; the
  copy that is temporarily replaced by the revision in trial is marked as "temporarily replaced by
  the revision in trial" and will not be archived.

## 12. Memory and Models

- **Thinking Intensity**: The service provider has specified the gear level
  (`supported_reasoning_levels` / `capabilities.reasoning.effort`) in the model list. Each of the
  four protocols has its own method of sending: `ResponsesModel` sends `reasoning: {effort: ...}`;
  `OpenAiCompatModel` sends `reasoning_effort`; `AnthropicModel` sends `thinking: {type: adaptive,
  display: summarized}` and `output_config: {effort: ...}` (choose `none` `thinking: {type:
  disabled}`); `GeminiModel` sends `generationConfig.thinkingConfig` (`thinkingLevel`,
  `thinkingBudget: 0` when `none` is selected, `includeThoughts: true` when turned on).
  `ModelCatalog` retains the gear position and order, cached with `ListedModel.reasoningEfforts`;
  missing and clear empty list distinction. The Anthropic list is filled out item by item according
  to `capabilities.effort.<level>.supported` and `capabilities.thinking.types.<mode>.supported`: when
  `adaptive` is accepted, the gear level is marked with the supported levels (from lightest to
  deepest), and `disabled` is also accepted with `none` in front; not accepted `adaptive` (only
  `enabled` can be considered according to the budget) or the empty list regardless of grade; the
  accepted way of thinking is saved as `ListedModel.thinkingTypes` and handed over to
  `AnthropicModel` through `ClientSetup.thinkingTypes`. The one that is not accepted will not be
  sent. The Gemini list only has a Boolean `thinking` and does not give gears. `ReasoningOptions`
  priority list; When there is no report, only the Responses family (including ChatGPT, xAI login)
  gives the general manual gear, and the compatible interface, Messages, and generateContent are not
  guessed (each model recognizes different gears and writing methods, and if you guess wrong, every
  request will be rejected). Which protocol is actually used is determined by
  `ProviderStore.adapterFor` (subscription and login always use this interface, not other protocols
  written in the list), model building and sharing among stalls. `ReasoningControl` is selected
  under the use card, and `RoleChoices.reasoningEfforts` is saved to `uses.<role>.reasoningEffort`
  according to the use. The same model can be different for different uses; "Use main model"
  inherits the strength of the main model, and clears its old selection when changing the model for
  this use. The default is not specified, and the old configuration will not be migrated to the new
  default; `ProviderStore.modelFor` is transmitted to the adapter via `ClientSetup`, and is only
  sent if explicitly selected. After Claude turned on thinking, the output limit was raised to 16K
  (thinking is also included in `max_tokens`); In the round with tool call, the signature of the
  thinking block and the address where it is received are stored in `Part.Thinking.opaque`
  (`SignedThinking`), and only the same address is returned (`redacted_thinking` is also returned).
  It is not returned when the address is changed or thinking is turned off. When the server rejects
  the selected gear, an error will be reported as usual and the gear will not be downgraded
  silently; the unsupported `include` will be removed and the strength will still be retained when
  retrying. After refreshing the list, the selected gears that are no longer supported will be
  retained and prompted, and the default can be restored.

- **Memory** (`core/memory/`): Three categories `MemoryKind` - facts, preferences, practices. Facts
  and preferences are written down immediately and can be revoked, and the practices require user
  approval. It is only recorded from what the user says (`UserUtterances`, with the page description
  of the screen, browser page, quoted Mina's answer, attachment, only the words typed by the user
  are included, and the quoted words are also included, `UserWords.said`), not from the screen and
  web page content; passwords, verification codes, card numbers, ID numbers, etc. are blocked by
  `SensitiveFilter`. Tools `memory_save`, `memory_search`; after the task is completed,
  `MemoryExtractor` is extracted from the user's original words in the background (the run that has
  read the web page is not extracted; it is also required that this run be initiated by the dialogue
  interface and the answer is handed over, `RunLearning`). Memory injection at the start
  (`MemoryContext`): If the total amount of memory is within `FULL_MAX_TOKENS`, only one item will
  be given (topped first, and the rest are in descending order of update time). If it exceeds, only
  the top and retrieved related entries (BM25 of `MemoryIndex`) will be returned. When the total
  amount is full, all the practices will be given. When retrieving, the practices will be listed
  according to their relevance, up to `MAX_PROCEDURES`. Article; Product path (`AgentLoop`) uses
  automatic mode, `Mode.Relevant` retains the old behavior. There are also separate user portraits
  (`ProfileStore`, tool `profile_edit`, changes will take effect from the next task). Memories and
  images are stored encrypted using keys in the Android keystore
  (`app/memory/EncryptedMemoryFile.kt`). The switches "Use Memory" and "Automatically remember what
  I said" are on by default.
- **Model**: Four interfaces - OpenAI Completions (Chat Completions), OpenAI Responses, Anthropic,
  Gemini (`core/model/`); you can also log in with a ChatGPT account and use the package quota (see
  "Responses and logging in with ChatGPT" below). The model is divided into three purposes
  `ModelRole`: main model, picture viewing model, and lightweight model. Speech-to-text is the
  fourth use, but it is not included in `ModelRole`. It is stored separately (`SpeechChoice`, see
  "16. Voice input"). What is selected for each purpose is clearly stated by the user
  (`RoleChoices`, in `app/settings/ProviderStore.kt`), and there is no default allocation based on
  capabilities: the main model is a specific model or has not been selected; the picture shows a
  specific model, "use the main model" or "not used" (new installations use it by default);
  lightweight is a specific model or "use the main model" (new installations default). Select not to
  block by capability mark (`ProviderStore.choose` does not reject), and write down the
  corresponding marks when selecting a specific model: select the main model to record tool calls,
  and select the model to view pictures. When running, press these two marks to decide whether to
  send tool definitions and attach drawings; when viewing pictures "use the main model", `configFor`
  gives the configuration of the main model and takes the view mark seriously (the user said it can
  view pictures), the main model still operates according to its saved mark. Ability labels are for
  reference only. The lightweight model is preferred for outcome determination, memory retrieval,
  intention determination, and route matching; after turning on the speed switch "Leave simple steps
  to the lightweight model", the simple steps are also handed over to it
  (`core/agent/ModelRouter.kt`, the lightweight model does not support the mobile phone step when
  calling the tool). "Viewing positioning has been verified" (`Capabilities.groundingVerified`) is
  changed in Settings › Developer Options. It affects the configuration of the current viewing model
  (`ProviderStore.setVisionGroundingVerified`). It is not displayed when there is no viewing model.
  The API Key is encrypted and stored in the keystore (`app/settings/KeyCipher.kt`).
- **Service Provider Default** (`app/settings/ProviderPresets.kt`): Pre-filled interface type,
  address and initial capability value, model name and Key are filled in by the user. Domestic:
  DeepSeek, Tongyi Qianwen, Kimi, Zhipu, Silicon Flow, Volcano Ark (bean bag, the model name can
  fill in the model ID or access point `ep-…`), MiniMax (domestic station `api.minimax.cn`), Step
  Star, Tencent Hunyuan (TokenHub `tokenhub.tencentmaas.com`), Baidu Qianfan
  (`qianfan.baidubce.com/v2`); foreign: OpenRouter, OpenCode Go, Anthropic, Gemini, OpenAI, xAI,
  Mistral, Groq; running on your own machine: Ollama, LM Studio; and finally "custom". The interface
  type of "OpenAI" is Responses (`ProviderConfig.Kind.OpenAiResponses`): the official document
  states that the new main model calling tool must use Responses, and Chat Completions with tools
  will be rejected; the column model is also `GET /models`. The rest are OpenAI compatible. When
  adding a "custom" service provider, the interface type can be OpenAI compatible, Anthropic,
  Gemini, OpenAI Responses (`ProviderConfig.Kind.withApiKey`, Responses ranked last), select
  Responses through `ResponsesModel`, adjust `{base}/responses`, and the column model is still `GET
  {base}/models`; Custom Responses requires the same Key as Anthropic and Gemini (only OpenAI is
  included in the custom service provider Compatibility does not need to be filled in, it is mostly
  a local service, see `AddForm.keyRequired`). The address of `ProviderPreset.selfHosted` (Ollama,
  LM Studio) is pre-filled with the default port of the machine (`http://127.0.0.1:11434/v1`,
  `http://127.0.0.1:1234/v1`). The form can be changed to the computer's LAN address
  (`AddForm.urlEditable`, the following sentence prompts how to change it). The Key does not need to
  be filled in (`AddForm.keyRequired`). The saved service provider name still uses the default name.
  The default list in the README is generated by `./gradlew updateReadme` from this file (see
  `ReadmeSections`).
- **Column model and connection service provider**: `ModelCatalog` (`core/model/ModelCatalog.kt`)
  calls each public column model interface (OpenAI is compatible with `GET /models`, Anthropic `GET
  /v1/models`, Gemini `GET /v1beta/models`, the latter two turn pages), returns the model name and
  the capabilities given by the interface (tool calling, picture viewing, context length,
  reasoning); those not provided by the interface are empty, and are given by `ModelOptions`
  (`app/settings/ModelOptions.kt`) follows the default initial value and is marked as unconfirmed.
  Failures are classified by reason (Invalid Key, Network, No List Provided, etc.). List interface
  404 / 405 / 501 is recorded as "does not provide a list", but by looking at it alone, you cannot
  tell whether this company really does not provide a list or whether the address and interface type
  are filled in incorrectly. You can tell by the following trial call.
- **One service provider, several protocols**: Some service providers (such as subscription-based
  aggregation services) use different protocols for models under one Key. Each model in the list has
  a `api` table, the key is the protocol (`chat-completions`, `messages`, `generate-content`,
  `responses`...), and the value contains `base_url` of this protocol (compatible interfaces can
  also be written as `compatibility.reasoning_field`). `ModelCatalog` Take the first one in the
  order of Chat Completions, Messages, generateContent, and Responses, and record it as
  `ListedModel.endpoint` (`ModelEndpoint`: protocol, address, thought field); One that cannot be
  said (such as `realtime`), write down the protocol name (`unsupportedApis`), `ListedModel.usable`
  is false, `ModelOptions.merge` is not listed, and the number of models of the service provider is
  not included. Users cannot choose a model that is bound to fail. The abilities in the same writing
  method are also recognized: `capabilities.tools.supported`, `capabilities.reasoning.supported`,
  `limit.context`, `modalities.input` (you can see the picture if you have `image/…`). `endpoint` is
  stored in `services[].models[].api` of `providers.json` along with the model list (the same place
  as `unsupportedApis`). When building the model `ProviderStore.modelFor`, check the `endpoint` of
  this model in the list remembered by the service provider. If there is, select the adapter
  according to the protocol (`ProviderConfig.Kind.of`: Chat Completions → `OpenAiCompatModel`,
  Messages → `AnthropicModel`, generateContent → `GeminiModel`, Responses → `ResponsesModel`), use
  its address, if not, just use the service provider's interface type and address; if you re-list
  it, if the address is changed, use the new one next time you build a model. The adapter connects
  to the version path (Anthropic connects to `/v1/messages`, Gemini connects to `/v1beta/models/…`),
  and the existing `/v1` and `/v1beta` at the end of the list address are removed from
  `ModelEndpoint.adapterBaseUrl` and will not be repeated. The key is always the one from the
  service provider. Compatible interface thought return (`OpenAiCompatModel`, not judged by service
  provider name, address or model name): each company puts the thought in `reasoning_content`,
  `reasoning`, or `content`, which is a block array, and thinks in the `thinking` block (the text
  block is the answer); When receiving a thought, write down the address and location where it was
  received (`ReasoningVia`, `Part.Thinking.opaque` exists), and put it back to its original place in
  the request with the tool (the field has the same name; the whole line that is divided into blocks
  is put back in chunks, with the thought block first). Only messages received from the same address
  will be returned, messages from other addresses and other adapters will not be sent, and service
  providers that do not recognize these fields will not receive them; bypass calls without tools
  will not be sent back. The list indicates `reasoning_field` (`reasoning_content` or `reasoning`),
  and thoughts of unknown origin (the history saved by the old version) are also returned according
  to it. The default "OpenCode Go" is like this: list address `https://opencode.ai/zen/go/v1` (no
  Key required in the list), interface type OpenAI compatible is just a fallback when the protocol
  is not written; models with only `responses` written (address such as
  `https://opencode.ai/inference/go/openai/v1`) are called by `ResponsesModel`.
- **Responses and logging in with ChatGPT**:`ResponsesModel`) tune`core/model/ResponsesModel.kt`,
  uniformly`POST {base}/responses`, put the context in every time`stream:true`Array: system prompt
  words`store:false`, the system message in the conversation is changed to developer message, the
  picture is`input`(When the model cannot be viewed, replace it with a placeholder sentence.) The
  tool is the function tool (`instructions`), the tool call and the result are`input_image`entry. By
  default, only`strict:false`;Sent separately when the user chooses the intensity of
  thinking`function_call`, the temperature and output upper limit are not issued (not charged when
  using ChatGPT package quota). Inference model thinking requests encrypted content
  (`function_call_output`), stored as is in the first tool call of this round`model`, put it back in
  front of the tool call during playback (remove the ones that are only meaningful on the server
  side)`instructions`); The server says it won’t accept it.`input`Remove it and send it again. only
  received`tools`(or`store`recorded as output truncated) is considered successful,`stream`according
  to`include`Return to`reasoning.effort`, the stream is disconnected before the end as a network
  failure (will be retried). Token consists of`include: reasoning.encrypted_content`Give: The API
  Key is fixed; you can change it to a new one (ChatGPT). If you encounter HTTP 401, change it first
  and then send it again. Dosage from`Part.ToolCall.opaque`) will be recorded in the Token usage.
  **Sign in with ChatGPT** (`core/chatgpt/`, API type `ProviderConfig.Kind.ChatGpt`) uses the path
OpenAI offers to open-source apps that run locally: a public client with no secret, authorization
code + PKCE (S256), and a fresh state, nonce and verifier for every login. `LoopbackCallback`
(`core/oauth/`, shared with OpenRouter) opens a free port chosen by the system on `127.0.0.1` and
accepts only one `GET /auth/callback` (other paths return 404; a state mismatch returns 400 and
keeps waiting). When the callback arrives, the login is finished first — check the callback,
exchange the token, verify the ID token, check the plan permission, at most 20 seconds — and only
then is the browser answered (it waits on that connection): success shows "Signed in, return to
Mina", failure shows the same reason as the UI, and the page button is an intent link that opens
Mina; timeout or cancellation closes it. The authorization page
`https://auth.openai.com/api/accounts/authorize` opens in the system browser: the first time it
registers with `client_id=dynamic_agent_client`, `agent_name_hint=Mina` and the device identity
`ext_agent_host_id` (`urn:uuid:…`, generated once per device); the callback returns a client_id
specific to that account, which is stored before the token exchange
(`settings/chatgpt-host.json`, next to the device identity, neither being a secret), and every later
exchange, refresh and re-login uses it (a re-login sends the previous ID token and email as hints;
after signing out it sends no ID token). `scope` is
`openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`, `resource` is
`https://api.openai.com/v1`. After the token exchange (`/api/accounts/oauth/token`, form encoded)
the ID token is verified: RS256 signature against `/.well-known/jwks.json`, issuer, audience
(= client_id), expiry and nonce; a grant without `chatgpt.tokens.use.direct` does not count as a
successful login (`signInWithChatGpt` ties this together). Rejections are classified by
`ChatGptAuth.failureOf`, the UI and the callback page say the same human sentence, and the
diagnostics record the error code: a non-JSON 403 / 429 is blocked by the protection layer
(`SignInFailure.Blocked`, change network or retry later); `unsupported_country` / `region` /
`territory` in the code or description means the region is unsupported (`SignInFailure.Region`);
`invalid_grant` means the code expired or was used (`SignInFailure.CodeExpired`); `access_denied`
and similar mean the user did not consent (`SignInFailure.Denied`); a plan or permission word
(`invalid_scope`, `plan`, `subscription`, `scope`) in the code or description, or a grant without
the plan scope, means Plus / Pro is required (`SignInFailure.NoPlanScope`); anything else is
"ChatGPT rejected this sign-in (error: code)" (`SignInFailure.Exchange`). The `error` returned by
the authorization page is classified the same way, and anything that names neither a region nor a
plan counts as `SignInFailure.Denied`. During login Mina is in the background while the user types
a password and two-factor codes in the browser, and Android may freeze or reclaim the background
process: the local port stays open with nobody accepting, the browser waits until it times out and
the authorization code may expire. So the login does not run in the UI scope but in the app-wide
`ChatGptSignIns` (`app/settings/ChatGptSignIns.kt`, which survives UI recreation and going to the
background, one at a time); it starts the foreground service `ChatGptSignInService` (`specialUse`,
attached to the quiet "Task progress" channel: "Waiting for you to sign in to ChatGPT in the
browser", with "Cancel"); if the service cannot start it only logs a line and the login continues.
The service watches `ChatGptSignIns.running` and stops itself when the login ends (success, failure,
cancellation, timeout). The last login failure (time, category, error code, a short redacted
description) is kept in memory only and written into "Copy diagnostics". The whole session
(`ChatGptSession`: client_id, identity, tokens, expiry, granted scopes) is treated as this
provider's "Key" and encrypted by the same `KeyCipher` into `provider-keys.json`. The access token
expires after an hour: `ChatGptTokens` exchanges the refresh token for a new pair when it is close
to expiry (less than 5 minutes) or rejected, storing the new refresh and access tokens together,
with only one refresh at a time; when the refresh token can no longer be used (`invalid_grant`
etc.) the tokens are cleared and "sign-in expired" is reported, while a transient network or server
error keeps them. Signing out first revokes the refresh token on the server
(`/api/accounts/oauth/revoke`) and clears the local tokens even when that cannot be confirmed,
explaining why; the provider, models and uses stay, so signing in again continues where it left off.
Deleting the ChatGPT provider forgets the client_id (the next sign-in registers again and may use a
different account) and keeps the device identity. The model list is `GET
https://api.openai.com/v1/models` (access token); the returned `models` keep only entries whose
`visibility` is `list`, in server order, with `slug` as the name and `display_name` as the label,
and `input_modalities`, `context_window` and `supported_reasoning_levels` fill in capabilities when
present; all calls go through `ResponsesModel` (`chatGptPlan`). Plan-quota errors:
`subscription_sharing_usage_limit_exceeded` (or HTTP 429) means the quota is exhausted
(`ModelError.ChatGptLimitReached`), `subscription_sharing_user_not_eligible` (or HTTP 403) means
the account cannot be used this way (`ChatGptNotEligible`); neither is retried, and the outcome
explains the reason and where to check usage (chatgpt.com/settings/usage).
`subscription_sharing_usage_unavailable` and other temporary unavailability are retried later as
503. UI: the ChatGPT row in the "Sign in with account" group at the top of Add provider opens a
panel with "Use your ChatGPT plan" and "Continue with ChatGPT" buttons (stating that Plus and Pro
work and Mina charges nothing extra), and after the first successful sign-in it explains once that
usage goes through the ChatGPT plan; the provider details show the account (email), manage usage
(opens chatgpt.com/settings/usage), and sign out or sign in again, with no API type, URL or Key.
- **Sign in with an account (xAI, OpenRouter)** (`core/oauth/`): next to ChatGPT, in the
  "Sign in with account · no Key needed" group at the top of Add provider (`ui/AccountSignIn.kt`:
  three rows, ChatGPT, xAI / Grok and OpenRouter; signed-in ones say "Signed in" and open the
  details, others open an explanation panel and only start on "Continue signing in"; xAI shows a
  risk warning and "Use an API Key instead"). All sign-ins run in `ChatGptSignIns`
  (`SignInTarget` decides which provider, and the foreground notification uses that provider's two
  sentences); on success the models are listed and stored, one is chosen if there is no main model
  yet, and that provider's details open (`ModelSetupUi.signedInProvider`). The details
  (`ModelsScreen.kt`) show the account, status, set-as-main-model, open the provider's account page,
  and sign out or sign in again; signing out clears only local data. Failure categories and error
  codes go into diagnostics. The local callback `LoopbackCallback` and `Pkce` live in `core/oauth/`
  and are shared by ChatGPT and OpenRouter; `SignInBrowser` opens the browser, returns to Mina and
  shows the result page after the callback.
  **xAI** (API type `ProviderConfig.Kind.Xai`): sign-in, encrypted storage, refresh and calls to
  xAI's own API are four separate steps. It uses the device code flow (RFC 8628,
  `DeviceCodeClient`, `awaitDeviceGrant`): the user opens the verification page in a browser and
  checks the short code, and the app polls for the token at the interval the server gives (adding 5
  seconds on `slow_down`, and only failing after 3 consecutive connection failures); the panel shows
  the same short code, its lifetime and "Copy code and reopen the confirmation page"; if the
  verification page's host is not on the allow-list, the fixed official page is opened instead.
  Access tokens, refresh tokens and device codes never go into logs. The whole session
  (`AccountSession`) is encrypted into `provider-keys.json` like an API Key; `AccountTokens`
  refreshes when close to expiry or rejected, and when the refresh token can no longer be used it
  clears them and reports "xAI sign-in expired" (`ModelError.SignInExpired.account`). `XaiAuth`
  first reads `https://auth.x.ai/.well-known/openid-configuration`; the issuer and the device-code
  and token endpoints must be https `auth.x.ai` (`OauthUrls`), otherwise it stops. The client reuses
  the public login entry of xAI's official command-line tool (not one registered for Mina), with
  scopes including `grok-cli:access` and `api:access`; the panel states the reuse risk before
  signing in. Calls go to Responses at `https://api.x.ai/v1` (`ResponsesModel`, `accountLogin`); an
  HTTP 403 means the subscription tier cannot use this API
  (`ModelError.AccountPlanNotAllowed`, not retried, and the message says to use an xAI API Key).
  **OpenRouter** (`OpenRouterAuth`, `signInWithOpenRouter`): official OAuth PKCE. The authorization
  page `https://openrouter.ai/auth` carries the local callback
  (`http://localhost:<port>/auth/callback`, listening only on 127.0.0.1), an S256 challenge,
  `key_label=Mina` and the state; a state mismatch returns 400 and keeps waiting, and no code with
  no state means the user refused; `POST /api/v1/auth/keys` (JSON: code, code_verifier,
  code_challenge_method) exchanges it for an OpenRouter Key. It is stored as an OpenAI-compatible
  provider following the OpenRouter preset, the Key is encrypted as usual, and `Provider.signIn`
  records `openrouter` (in `providers.json` under `services[].signIn`); an authorized provider and
  one where the user typed a Key are separate providers (`findOrAddProvider` separates them by
  origin), authorization never replaces the user's Key, and the UI shows "OpenRouter (account
  sign-in)". Signing out deletes the local Key (`ProviderStore.signOutOpenRouter`) and suggests
  deleting it on OpenRouter's Keys page. See `docs/dev/account-signin.md` (which also explains why
  Claude subscriptions, Google accounts and MiniMax accounts are not supported).
- **Request header and session number** (`core/model/ClientHeaders.kt`): Each request sent to the
  model service (the four protocol adapters Chat Completions, Messages, generateContent, Responses,
  one-time and streaming, and the `ModelCatalog` column model) carries the header given by
  `ClientHeaders.of`, and the Key (or ChatGPT access token) header is still added by the adapter
  itself; "Log in with ChatGPT" also carries `User-Agent`, it does not declare a session header and
  does not send a session number. `User-Agent` is `Mina/<version>` (App uses `BuildConfig.VERSION_NAME`
  to `ProviderStore`, core does not write the version number). Some service providers require each
  conversation to have a fixed session number (route and reuse the prompt word cache accordingly,
  and reject it if it is not provided): by default, use `ProviderPreset.sessionHeader` to declare
  the first name (currently only `x-opencode-session` of "OpenCode Go"), and record it on the
  service provider when connecting (`Provider.sessionHeader`, there is `providers.json`)
  `services[].sessionHeader`; The old version does not have this item, and it will be filled in
  according to the default address that matches the address when reading). The undeclared service
  provider will not issue a session number, and the code will not be judged by the service
  provider's name. The session number is `ModelSession.of (conversation id)` (UUID v3, irreversible,
  does not send the conversation id itself): The three model factories of `TaskEnvironment` have one
  more parameter, `ModelSession`, `TaskRun` Build the main, lightweight, and picture-viewing model
  according to your own dialogue. Use these for verification, intention judgment, ending judgment,
  and explanation after stopping. `TaskService` also uses the same conversation ID when starting the
  conversation title; the conversation number for each run and each bypass call in the same
  conversation is the same, but it will be different when changing the conversation. Requests that
  do not belong to any conversation (column model, connection test) use a new number of
  `ModelSession.fresh()` each time.
- **Clear text http** (`res/xml/network_security_config.xml`, referenced by `networkSecurityConfig`
  in the list): Most of the model services that users run on their local machine or LAN are only
  http, so the entire App allows plain text (`base-config cleartextTrafficPermitted="true"`), and
  the certificate only trusts the system. Nowhere else in the code does it reject
  http:`ModelCatalog` and the adapter only requires it to be a valid http(s) address, and the add
  form only requires it to start with `http://` or `https://`. When adding a service provider, plain
  text http For addresses that are not on the local machine or the local area network
  (`LocalHosts.isLocal`: localhost, 127/8,::1, 10/8, 172.16/12, 192.168/16, 169.254/16, fc00::/7,
  fe80::/10, `.local`), please note (`AddForm.unencryptedWarning`) under the form and do not stop
  it. The built-in browser can therefore directly open http web pages: in the past, the system
  rejected (`ERR_CLEARTEXT_NOT_PERMITTED`), `WebScreen` and then changed to https and reopened it.
  Now it is opened according to the original address. The retry of changing to https is only useful
  when the device policy or WebView still rejects clear text; the clear text sub-resource in the
  https page is still rejected as `MIXED_CONTENT_NEVER_ALLOW`.
- **Network proxy** (`core/net/NetProxy.kt`, `ProxyAddress.kt`, `LocalHosts.kt`; set in
  `app/settings/NetProxyStore.kt`): Set the line "Network proxy" under the model in the "Basic"
  group on the homepage (`ui/NetProxySettings.kt`, only change this place), fill in `http://[user:password@]
  Host: port`, also recognize socks5, socks5h, https (`ProxyAddress.parse` Check, if it is written
  incorrectly, it will not be saved). If left blank, it means direct connection. The address may
  contain an account password, which is encrypted by `KeyCipher` (Android keystore) and saved as
  `settings/net-proxy.bin` like the service provider's key. No clear text preferences are entered,
  no logs are written, and only the protocol, host, and port are displayed on the interface
  (`ProxyAddress.toString` does not contain an account password either). Earlier it was only used
  for containers and existed as `container-proxy.bin` (another key): `NetProxyStore`. When reading
  it for the first time, it was decoded and saved, and the old file was deleted.
  - Who takes it: OkHttp client built by `NetProxy.shared.client(…)` - model client of
    `ProviderStore` (four adapters one-time and streaming, voice transcription, connection test) and
    column model, client with account login and token exchange, `UpdateChecker`, built-in browser
    download (`WebStack`); sandbox command via environment variables (see Section 6 sandbox). Don't
    use it: remote transfer (`RelayGateway`), running record export (`OtlpSender`).
  - How to go: The client is installed with `NetProxy.selector` (`ProxySelector`). Each request now
    asks: `LocalHosts.bypassesProxy` (the paragraphs of `isLocal`, plus the machine name without
    dots, `.lan`/`.home.arpa`/`.internal`, 100.64/10) for direct connection. Otherwise, use HTTP or
    SOCKS proxy; SOCKS The domain name is handed over to the proxy for resolution (socks5 is the
    same as socks5h). `https://` proxy (hold TLS with the proxy first) OkHttp does not support,
    `ProxyAddress.forApp` is false, Mina's own connection is direct, and the settings page states
    that it is only for containers. Streaming replies are still read paragraph by paragraph, and the
    agent does not change the reading method. The settings have been changed to `NetProxyStore` and
    adjusted to `NetProxy.changed()`: re-parse and clear the idle connections of each client
    (ongoing requests will still be completed).
  - Account password: The HTTP proxy is `NetProxy.authenticator` (OkHttp's `proxyAuthenticator`).
    When the proxy returns 407 and before establishing the https tunnel, `Proxy-Authorization:
    Basic` is provided; if the same request is rejected and is rejected, do not try again. The SOCKS
    proxy can only pass one `java.net.Authenticator` in the whole process to: `MinaApp`.
    `NetProxy.socksAuthenticator` is installed at startup. It only answers the query that the
    protocol is SOCKS and the host port matches the current proxy, and does not answer anything
    else.
  - Error report: `HttpSupport` When the connection fails, the request that went through the proxy
    is classified as `NetProxy.classify` as `ModelError.Proxy` (`ProxyFailure.Unreachable`:
    connection refused, proxy host not found, connection timeout, tunnel establishment failure,
    SOCKS handshake error; `ProxyFailure.AuthFailed`: proxy rejected the account password), HTTP 407
    is also considered the latter; interruption during reading reply is still `Network`.
    `userReason`, trial call (`ModelSetupViewModel.proxyReason`), and column model
    (`ListFailure.ProxyUnreachable` / `ListFailure.ProxyAuth`) are all said to be problems with the
    agent, not the Key or the wrong model. If you cannot connect, you can try again. If the account
    and password are incorrect, you will not try again, and the model will not be deactivated.
  - Update download: The system download service (`DownloadManager`) does not use the App's proxy,
    so when the proxy is set up, `ApkUpdater` is downloaded in the process through the proxy instead
    of `ProxiedApkDownload` (first write `.part`, complete and then change the name; the process is
    considered a failure if it is killed, just try again). When installing, `UpdateFilesProvider` is
    used to give the system installer a read-only address; the verification is the same as before.
  - Built-in browser (`cap/web/WebProxy.kt`): When WebView supports `PROXY_OVERRIDE`,
    androidx.webkit and `ProxyController` set the same proxy for all WebViews in this process. The
    direct connection rule is `LocalHosts.BYPASS_RULES` plus a simple host name; when the HTTP proxy
    requires an account and password, `WebTab.onReceivedHttpAuthRequest` only answers the host of
    the current proxy (up to 3 times for each label). When WebView does not support it, or the proxy
    is SOCKS with account and password (the kernel does not support SOCKS authentication), the
    browser connects directly, and the line on the settings page states this. The WebView will not
    be loaded for this purpose when launched without a proxy.
- **Storage of service provider and model configuration** (`app/settings/ProviderStore.kt`):
  `Provider` is a connected service provider (id, name, interface type, address, and the most
  recently listed model), `ProviderConfig` is a model hanging under it (note `providerId`, the
  interface type and address are copied from the service provider, and the Key is taken from the
  service provider when building the model). The model configuration is an internal layer of storage
  that cannot be seen by users: it is created on the spot when selecting a model
  (`ProviderStore.use`), and is deleted when it is no longer used (every time the purpose is
  changed, the service provider is deleted, and it is sorted when it is read in; the key is stored
  according to the service provider, and the key is not changed when deleting the configuration). In
  `providers.json`, `services` stores the service provider (including the remembered model list),
  `providers` stores the model configuration, and `uses` stores three choices for use
  (`{"use":"model","config":<config id>}`, `{"use":"main"}` or `{"use":"none"}`); the Key exists after
  being encrypted by `KeyCipher` `provider-keys.json`, one copy for each service provider. The old
  version only has model configuration, and the key is stored according to the configuration id:
  when reading, it is grouped into service providers according to `ProviderConnection.keyOf`
  (interface type, address, name), and the key is taken from any item in the group, and then cleared
  according to the configuration id. The old version of `roles` only remembers the explicitly
  specified configuration id: when reading, write the allocation that is actually effective at that
  time as `uses` - if the main model is not specified, take the first configuration. If the main
  model is not specified, "use the main model" if you can see the picture, otherwise do not use it.
  If it is not specified, "use the main model" (it is only done once, the migrated one will not be
  moved, and repeated execution will have no side effects). When deleting the service provider,
  delete the configuration below it together with the Key, and use its purpose to return to the
  default (the main model becomes unselected, which is not used when looking at the picture, and the
  main model is used lightly).
- **Configuration model page** (`app/ui/ModelSetupScreen.kt`, status is `ModelSetupViewModel`):
  "Configuration model" and settings › "Model" when getting started (`app/ui/ModelsScreen.kt` Just
  the same page without guidance) share the page and status, and the user only cares about two
  parts: purpose (three cards, each indicating what is selected: model name, "main model used" and
  who the main model is, "not used" or not set) and service provider (one row for each company,
  indicating which purpose it is working for). When getting started, the service provider comes
  first. When there is no service provider, the adding form is placed directly on the page. Select
  the main model in "Next" at the bottom before clicking it; in the settings, the purpose is first,
  there is no bottom button, and the changes take effect immediately. Add a service provider, select
  default or customized, fill in the Key and "test and save": first list the model, and then try to
  call it again after the list is found (or the service does not provide a list) - press the order
  of `ModelOptions.candidates` (the company's own model first: the model name or display name has
  the default `ProviderPreset.familyTokens`, such as Tongyi Qianwen's qwen; then list the tools that
  can be called; In the same file of your own, the latest version in the model name comes first
  (`settings/ModelOptions.kt`’s `ModelVersion`: `x-3.1-pro`, `qwen3`, `claude-4-5`, `4o` and other
  version numbers, the parameters and context length at the end of b / k are not counted; the
  official version of the same version comes first, `latest` alias comes second, `preview` / `exp` /
  `beta` and so on; if there is no date written in the same file, go first, then press
  `-2025-08-07`, `-0827`, the newer date is first; if the version cannot be read, go behind the one
  that can be read; do not comment on which file is better, flash, pro, lite, the same version is in
  the order of the list; Models from other companies are not arranged by version - the old model is
  often first in the service provider list, and the old model may have been offline); the rest are
  in the order of the list; those that clearly say that they will not call tools, such as
  vector/rearrangement/speech/drawing, are not tried - the `type` in the list and the output mode
  state that it should be followed (`ListedModel.chat`), but the words in the model name are not
  written; If there is no candidate, try the first one) Give the model a parameterless tool
  `get_time`, ask it to call, and reply with an upper limit of 1024 tokens (enough for one tool call
  together with the thought of the model first); Messages and generateContent are given; Chat
  Completions are not given, `max_tokens` and `max_completion_tokens` are recognized differently,
  and the entire request is rejected if the error is given; Responses are not given, the ChatGPT
  package does not accept `max_output_tokens`), and the real task takes the same path to build the
  model (`ProviderStore.probe` uses the same `modelFactory`, the request header and session number
  are the same, and the session number is new), and you have to wait up to 20 seconds (you do not
  need to go through this form to log in with ChatGPT, do not try: the login itself has been
  verified); Key No, the missing request header required by the service provider is exposed here,
  and the error reported by the server is displayed as it is and does not exist.
  `ProviderStore.probe` returns to `ProbeResult`: the tool call (`ToolCalled`) is returned and
  passed; I only replied (`NoToolCall`), indicating that the interface is ok, but this model does
  not call the tool. If it fails, it is a problem with the model itself (it only replies, or the
  adapter gives `ModelError.ModelNotAvailable`, the account is not opened or does not have
  permission, `ModelNotFound`, `ToolsUnsupported`) and then try the next candidate, up to 3
  (`ModelSetupViewModel.PROBE_CANDIDATES`): the passed ones are the main models, and on the page,
  under the service provider list, say which ones tried first failed (`ModelSetupUi.probeNote`);
  Even if it fails, it still saves the service provider (the key has been proven to be correct) and
  the main model. Tell me why the first one failed and what I tried. The next one is "select the
  main model". This one is recorded as `ModelSetupUi.pickToProbe`. When selecting the main model in
  it, try calling it first (`ModelSetupUi.probeBeforePick` → `pickManual`). If it doesn't work, tell
  the reason and don't select it; test again or close it when the main model is selected. The first
  one failed due to other reasons (Key, network, address, timeout, server error) and is not saved;
  Subsequent candidates who failed due to other reasons will not be tried again and will not pass
  the test. The adapter thinks "this model is not suitable" and uses the same
  `core/model/ModelAccessErrors.kt`: HTTP 400 / 403 / 404 error content contains tools that are not
  supported ("does not support tools"...), this account cannot be used ("not activated" "not
  authorized to access this model" "does not have access to the model" "Model.AccessDenied" "not
  activated" "no permission"..., bring the original words of the server), There is no such model
  ("model_not_found" "model not exist" "not found for API version" in 400 / 403..., Bring the
  original words in the JSON error report) These types of statements are given as
  `ToolsUnsupported`, `ModelNotAvailable`, `ModelNotFound`, and the model has been offline ("no
  longer available", "has been retired", "model... is deprecated", "offline"...) can also be counted
  as `ModelNotAvailable`, with the original words; HTTP 429 is only considered `ModelNotAvailable`
  when it means that this account has a quota of zero on this model ("limit: 0" ""quotaValue": "0""
  "doesn't have a free quota tier") (it's useless to wait, just change the model, try calling and
  then try the next one), other 429 is still limited or the quota is used up; the rest are handled
  according to the status code of each adapter, 401 is always Key For the problem, 404 is given as
  `ModelNotFound`, and the original words in the JSON error report are also included (not JSON is
  not included). If the model is not listed (the list interface does not exist, or the list is
  empty), do not save it directly: the form requires a model name (`AddForm.noList`, the example of
  the input box is according to the interface type, `ModelSetupCopy.modelExample`), and then use it
  to try calling, and save it as a service provider that does not provide a list and use it as the
  main model. If it fails, it will not be saved. There is a "manually filled in model name"
  (`AddForm.manual`) on the form at any time. It will be automatically expanded when the column
  model is wrong and there is no hand to fill it in. If it is filled in (`AddForm.typedModel`), just
  try this one. If the list is drawn (whether it is there or not), if it cannot be listed, or if
  other errors occur (the Key is wrong, 5xx, the response is not JSON), you can still try it. The
  conclusion of the trial call has the final say. If it passes, save it together with the list
  drawn; Those not in the list are recorded as `Provider.typedModels` (save with the service
  provider, refresh the list without losing it, `ModelOptions.ofProvider` is still listed,
  `ModelOption.typed` is marked "hand-filled"); when there is no hand-filling, the column model
  error still says the reason (the Key is wrong, it says the Key is wrong), and does not exist; the
  adapter gives 404 when trying to call back `ModelError.ModelNotFound(model)`, the form clearly
  states which model it is and what the model name probably looks like. If it is customized, it also
  says that the address or interface type may be filled in incorrectly. If the host name of the
  custom address is the same as a certain preset (`ProviderPresets.forHost`, except for the service
  you run), you will be reminded to use that default. Just change it and keep the key. If the
  address is the preset one, but the interface type is different, you will also be told at the same
  time. The name of the model that was responded to by the trial call is recorded in
  `Provider.verifiedModels` (saved with the service provider; the old file does not exist, and if
  the list is not provided at that time, do not try to call it). It has become a common practice.
  `ProviderStore.connect` saves this company and the list (the same interface type, address, and
  name are updated as the original one), and the name is the default name; For customized ones, you
  can give a name in the form (`AddForm.name`, up to `ModelSetupViewModel.MAX_NAME` characters
  (according to code points, emoji will not be cut into half), do not fill in `AddForm.defaultName`:
  the host name of the address, the address is a default name preset by a certain company, and this
  default name is written in the prompt of the input box), the same address is saved with different
  names (for example, two of the same transfer station) Key), can’t list the reason, doesn’t exist.
  When you connect to the first store and there is no main model yet, one of its models will be
  automatically selected. On the purpose selection page (for `app/ui/ModelsScreen.kt`), you can see
  that there are "Use Main Model" and "Don't Use" at the top of the picture, and "Use Main Model" at
  the top of Lightweight. All models are listed below by service provider (you can filter by name in
  many cases). The tags are for reference only, and are not filtered or grayed out. You can manually
  fill in the model name under each company (the list is not provided, and there is nothing in the
  list). If you fill it in manually, try calling it first (`ModelSetupViewModel.pickManual`, The
  main model also needs to be able to call tools), select it only after passing it, Record it as
  verified (if it is not in the list, it will also be recorded as filled in by hand). If it doesn't
  work, please tell me the reason below; The rest will take effect and return with one click. For a
  model (`ModelSetupUi.unverified`) that is in use but has not been verified by a service provider
  that does not provide a list, the service provider's line reminds "Failed to pass the test", and
  the details page has "Retest" (`ModelSetupViewModel.retest`). When the model call in the task is
  404, the failure reason indicates which model does not exist, go to Settings › Model to check the
  model name or use the corresponding preset; If there are original words in the server-side JSON
  error report, do not conclude that the model does not exist. Instead, quote the original words
  ("Server reply:..."), say that the model name may be wrong or the service is temporarily
  unavailable, and then give the same checking suggestions (remove the last period from the original
  words in the sentence, and the same is true for the "server description" of ChatGPT quota and
  subscription level); Please indicate which model cannot be used by this account, go to Settings ›
  Change the model or activate it in the service provider's backend; if the model does not support
  the tool, please indicate which one will call the tool (`core/agent/StopRules.kt`'s `userReason`;
  if these lightweight models are encountered, the main model will be used for the remaining steps
  of this task). The service provider details (same file) include connection (status, interface,
  address, Key can be replaced), up to three models (the one in use first, marked for which purpose
  it is used for) and "View all N models", delete (specify which purposes need to be re-selected
  before deleting); the search box of the "All Models" page is fixed at the top, and the upper right
  corner is refreshed and re-listed. Click any model to pop up the "Use" main model/view
  model/lightweight model from the bottom, and follow the same operation as the selection page. The
  names, actions and descriptions of the three uses are only written once in `ModelsCopy`. When
  opening the selection page or service provider page, service providers whose lists have not been
  remembered (for example, those migrated from old versions) will be listed once.
- **Token usage** (`core/usage/`): Accounting is done at the source, not by each caller. The models
  that can be called in the App are only taken from `ProviderStore.modelFor` (`buildModel` is
  private to it), and they are all measured by `metered` (`MeteredModel.kt`) before going out: the
  name and capabilities are still the same, exceptions are thrown as they are, and each text is
  called back as usual; At the end of each call, a record is recorded - the service provider id, the
  current service provider name, the model name, and the purpose (`UsagePurpose`, which is
  `ModelRole` when taking the model: "Use the main model" to view pictures, light calls are recorded
  under the name of view, lightweight), those with usage are recorded as input (including cache
  hits), output, cache hit tokens, and those that do not report usage or throw errors (including
  cancellation) only record the number of times, not estimated. Side calls such as main loop, ending
  judgment, intention judgment, picture viewing, memory retrieval, route matching, skills and App
  knowledge drafting are all counted; the column model is not a dialogue call, so it is not
  remembered. The ledger `UsageLedger` is accumulated according to the UTC hour × service provider ×
  model × purpose, and only exists on the local machine `files/usage-v1.json` (the same as other
  settings, it is JSON; One copy of the key table, and the rows are compact arrays): accounting is
  only added in memory, multi-thread safe, the whole copy is written atomically by the background
  thread in about 2 seconds after the change, and the last few seconds are lost at most when the
  process is killed; only 400 days are left. Statistics `UsageQuery` is a pure function, and the
  local time zone is converted during statistics: "24 hours" is the rolling last 24 full points
  (including the hour at this moment) in hours, "7 / 30 / 90 days" is the local calendar day
  including today, "custom" is the local start and end dates (both ends included), in days, and more
  than 45 days in weeks (every 7 days from the first day) Day one grid, `UsageStep`); An hourly
  bucket falls to the local day according to the time it starts (the day of daylight saving time is
  still correct). Each grid contains the token of each service provider, which can be stacked and
  drawn. Each service provider has a fixed order (`ProviderUsage.order`: connected ones in order of
  connection, deleted ones in the back, according to the earliest accounting time), and the color
  matching is based on the classification color in the theme (`MinaColors.series` three types, the
  rest are gray and are classified as "other" in the trend chart). Changing the scope and selecting
  the service provider will not change the color; the text will always use ink color. If the service
  provider deletes the history but it still exists, use the name at the time of accounting and add
  "(deleted)". Purpose is only used as a dimension of storage. The interface does not differentiate
  between purposes and does not display prices. Settings › Write the total of the past 7 days in one
  line of "Token Usage"; Click on a page (`app/ui/UsageScreen.kt`, no next level): time (24 hours /
  7 days / 30 days / 90 days / custom, the bottom line writes the actual range, custom with "change
  date"), total usage card (always all service providers: big number, input / output / cache hit /
  number of calls), and then three pictures - "usage trend" (pillars stacked by service provider,
  legend, Two dotted grid and scale on the right, Click one bar to see the total of this grid and
  the usage of each household), "By service provider" horizontal bar ranking (for usage and
  proportion, click on one to select, and then click to cancel. When selected, other companies will
  fade, and other companies will fade to a quarter in the trend chart), "By model" horizontal bar
  ranking (the upper row of capsules and service provider rankings are the same choice; I selected
  only the model of this company, the horizontal bar is the color of its service provider, and the
  proportion is calculated according to this); the number of times of reported usage and "the number
  is subject to the return of the service provider" are written in the footer.

## 13. Operation record

Write a record for each run (`app/record/RunStore.kt`): `run.json`, `events.jsonl` and screenshots
under `files/runs/<time>-<id>/`, and keep the latest 30 times. For event types, see
`core/record/RunEvent.kt` (model reply and usage, tool results, observation, activity, confirmation,
takeover, source, compression, outcome determination, etc.); before writing, `Redactor` masks the
password box input and key-like string. "View Process" on the interface opens the timeline
(`app/ui/RunTimelineScreen.kt`): the top is the goal, standard, ending, time, number of model calls
and other details, which are collected in "More Details". The vertical steps
(`components/StepLine.kt`) are listed below step by step. Click on the step with the picture to see
the picture at that time. There is a "running process" under the steps on the runtime dialogue page
of the task: the number is taken according to the dialogue where the card is located
(`TaskCenterState.run (conversation).runId`, which is the same as `endedRunIds` at the end, and does
not need to be the number in the external display of the service); the ending at the top of the page
is written as "in progress", the record is reread every second, the steps are long, and it does not
automatically scroll to the bottom; there is an end record or the task is no longer there
(`RunTimelineViewModel`) `alive`, the kind where the process is killed and the record is not
finished) will stop.

**Verbose Trace** is turned off by default. The debug version and performance test version can be
turned on in "Settings → Developer Options → Run Recording" and will be collected starting from the
next task. Turning it off will stop recording. Each main loop request (including retransmission) and
top-level tool call of the four model interfaces is recorded as a span. In "View Process → Detailed
Trace", you can compare the first changed block in adjacent requests for the same model. The cache
usage is not reported and the hit is zero are displayed separately.

- "Save this Trace" exports an OTLP Protobuf file (`.pb`) containing only diagnostic metadata by
  default. Check "Attach desensitized text" to save the ZIP, which contains the same copy of
  `trace.pb` and the desensitized text named according to the span ID.
- Click "Send via OTLP/HTTP" in "Detailed Trace", fill in the complete traces receiving address, and
  optionally fill in the authentication header. After confirmation, only the default diagnostic
  metadata of `.pb` is sent, and the authentication value is only used for the current request. The
  public network address must use HTTPS, and the local machine, LAN IP, and the `.local` name
  resolved to the local address can use HTTP. The interface displays the receipt confirmation, the
  number of rejected spans or HTTP errors. After the transmission fails, you can still view or save
  the archive locally.
- When encountering "partial record", first look at the `mina.trace.partial_reasons` of the root
  span. To locate the truncated chunk, look at `mina.block.path` and `mina.truncation.reason` for
  `mina.trace.truncated` events on the model span. A single run has a body budget of 8 MiB and
  records a maximum of 1,024 subspans.

The total capacity of the application private archive defaults to 100 MiB and can be adjusted in the
developer options. When there is insufficient space, the oldest completed archive will be cleared
first, and the archive that is being recorded or exported will be retained.

**A summary of everything** (`core/record/RunMetrics.kt`): `RunMetrics.of(events)` is a pure
function, only looking at the events in the running record; the time is calculated from the start
event (previous loading of knowledge and preparation of the mobile phone are not counted).
- The total duration reaches the ending event (the last event that has not ended is recorded as
  unfinished); the ending also has a failure category (`kind` for the ending event, which is the
  lowercase name of `FailKind`) and an ending that is judged after handing in the answer. Completed
  = The application was successful (including those that could not be verified), and it was not
  judged that only part of the application was completed or that it could not be completed.
- **First Feedback**: From the moment when the user sees the actual progress of this run for the
  first time - the earliest truly executed model reply is received (`repliedAtMs` of the reply
  event; the reply with the call is not recorded until it is executed, so the receipt time is
  recorded separately). At this time, the interface displays its progress description, things to be
  done or answers; or the earliest activity (the first step is completed when the playback does not
  go through the model). Replies from lightweight models that have been reassigned are not counted;
  when responses are displayed paragraph by paragraph, the first paragraph is earlier and there is
  no record, so it is not counted.
- **Back and forth with the model**: The number of model reply events (requests from the main loop,
  including dispatched lightweight model replies); token is the sum of the usage of these replies,
  and the additional number of times if the usage is not reported. Bypass calls (intent judgment,
  verification, image viewing, compression) and failure retry without sending reply events: Run
  `RunUsageTally` into its own coroutine context, and the model applied to `metered` will add a sum
  for each request, and at the end it will be recorded as `RunEvent.ModelCalls` (all requests, the
  number of unreported usage and token). The ending judgment and memory retrieval after handing in
  the answer are outside this context and only go to the Token usage page.
- **Time direction**: Model thinking is the sum of the request time of each reply (failed requests
  and back-off before retry are recorded separately); screen reading/operation is the execution time
  of top-level tool calls (model adjustment, playback), divided according to the type of activity
  given by the only execution entrance (the risk declared by the tool is read-only, and the rest are
  operations), and there are no new timing points. The outer call time of a batch of actions and
  page scripts already includes sub-steps: reads in sub-steps are counted as reads, and the rest are
  operations and are not counted repeatedly; control calls such as waiting, asking the user, please
  confirm, and handing over to the user are not counted as reads or operations. Others = total time
  minus these three items: waiting for user, retry backoff, control call, verification and other
  overhead.
- The writing thread of the running record calculates as it writes (`RunMetrics.Builder`, leaving
  only a few numbers of the results of each tool). When closing, the summary is handed over to
  `RunMetricsStore`: `files/run-metrics.jsonl`, one per line, leaving only the last 500 copies, and
  will not be cleaned with the 30 running records; the ending will be added after the judgment.
  Lines that cannot be read are skipped, and the entire line is replaced when writing. There is no
  more reading or writing on the main loop.
- Interface: The top line of "View Process" reads "Total 2 minutes and 13 seconds · 14 times back
  and forth with the model · 86,000 Tokens · First feedback 3.2 seconds". Click to open the model
  thinking/screen reading/operation/other time and proportion, input/output/cache hit token, and
  number of steps (`app/ui/RunMetricsUi.kt`; the writing method of token is the same as the Token
  usage page). Developer Options › "Run Statistics" calculates the completion rate, time and first
  feedback based on the last 10 / 30 / 100 times or all, the average number of round trips and
  tokens, and the time direction (`RunStats`). You can copy or share the summary of these times into
  JSON.
- The "Export Usage Records" in "View Process" saves this round of usage details (JSONL) through the
  system file selector, and is also applicable to old records that are still retained. Each
  `model_reply` retains the step number and original Token count, and multiple replies for the same
  step are listed one by one. The input contains cache hits, missing counts indicate unknown. The
  `compressed` event retains the time and `foldedSteps` in the original order, and the old record
  does not give it a separate step number. `model_calls` is the total total including bypass calls.
  Export only takes the diagnostic field, and the lines that failed to parse are marked with
  `unreadable_event` with the original line number.
- Evaluation: `Observed` for each question is accompanied by `RunMetrics` and `EvalReport`. There is
  an additional section "Running Summary" (summary and one line for each question), and the result
  directory is written as `metrics.json` (`EvalReport.metricsJson`).

## 14. Prompt words

The fixed text seen by the model is all in `core/src/main/resources/prompts/`, and the layering and
rules can be found there. `README.md`: `system/` (identity and general criteria), `capabilities/`
(one paragraph for each ability, only spell it in after the set of tools is expanded), `tools/`
(description of each tool), `entries/` (short description when the entrance tool is collected in
that group), `feedback/` (feedback of tool results and main loop), `roles/` (internal roles such as
verification, ending judgment, memory retrieval, skill draft, etc.). The system prompt word is
spelled by `SystemPrompt` (`core/agent/SystemPrompt.kt`) at the beginning of the task, and is only
re-spelled when a group of tools is expanded (paragraphs are in a fixed order); all text has a gold
sample test, and the full picture and size of the four situations of closed, mobile phone only, web
page, and fully open are in `prompt-surface/layers/`.

## 15. Interface and Visibility

- **Style**: Color (light and dark sets, follow the system), font size, size, rounded corners, and
  animation duration are all tokens, in `app/ui/theme/` (`MinaTheme`, `Colors.kt`, `Tokens.kt` of
  `Theme.kt`); line icons are in `app/ui/theme/Icons.kt`; common components such as group cards,
  list rows, switches, buttons, top bars, and glass input fields are in `app/ui/components/`. The
  page only selects colors and components from here, without writing dead color values. The
  background of the glass input field is blurred starting from Android 12, and earlier systems use
  an opaque background.
- **Navigation** (`app/ui/MainActivity.kt`, `app/ui/Navigation.kt`): The dialogue page is the root
  page, and the other pages (settings, models, memory and skills, scheduled tasks, browsers, running
  processes, sources, mobile operation guides, welcome and configuration models for getting started)
  are pushed into the page stack `NavStack`, and the return key and the return button on the page
  return to the previous page (entering from the sidebar returns to the open sidebar, entering from
  settings returns to settings). Each page is entered and exited only through callbacks.
- **Sidebar**(`app/ui/ChatDrawer.kt`): The menu in the upper left corner of the conversation page
  slides out from the left. The top is the logo, Mina and ready status; the entrance includes new
  conversations, scheduled tasks, memory and skills, browser, and settings; the bottom is "In
  Progress" (the current conversation and other conversations with tasks running can be cut through
  in parallel; "Backstage Screen ›" on the right side of the section title to enter the background
  screen page `app/ui/ScreensScreen.kt`: One card for each running task, write what it is doing,
  which App it is using (the connected built-in assistant rechecks the top App every time the task
  does something, without polling), status, click on the card to return to its dialogue, "View
  Screen" to expand the real-time screen in the card (only subscribe when expanded, reuse the speed
  limit of the capsule), click on the screen to enlarge to the full screen, pinch and zoom; Do not
  write things such as screen numbers and pixels for development; the data is taken from the own
  stream of each task and can be seen on screens that are not displayed in parallel), "pinned"
  (pinned conversations, the most recently pinned first) and "recent" conversations
  (`drawerSections`); Each conversation is only listed in one paragraph: the pinned dialogue has
  tasks listed in progress (also with pushpins) when running, and returns to the top after running.
  Other dialogues and tasks have small dots on the menu buttons during runtime. Long press a saved
  conversation (vibrate it; it is "Conversation Operation" in accessibility) to pop up the menu in
  place (`app/ui/ConversationMenu.kt`'s `LongPressMenu()`, long press the message on the
  conversation page also uses it): the entire screen becomes dark, and the subsequent conversation
  page and sidebar are still blurred in Android 12 (earlier systems only dimmed); The row was
  "raised" from its original position into a slightly enlarged, shaded white card (the content is
  the same as the row in the side column), followed by a narrower, left-aligned white card, followed
  by "pin to top" (pinned to the top is "unpin"), "rename", and red text "delete", with text on the
  left and icons on the right, with thin lines separating items. If it cannot fit at the bottom (the
  row is near the bottom and the keyboard is playing), place it on top of that row. If it cannot fit
  on both sides, move the row up and connect the menu below without going beyond the status bar,
  navigation bar and keyboard (`placeMenu`). Click outside and press return to collapse (play the
  animation that appears backwards); when the animation is turned off, the appearance and collapse
  will be directly in place without scaling. When the menu is open, the content behind it is not
  accessible. The menu has a panel title of "dialogue operation" and "collapse" operation. Each item
  can be focused and the name can be read. The menu is drawn on the same layer as the dialog page,
  covering the entire screen. Press and hold the position of the line in the window to position it.
  New conversations that have not yet been saved to the list will not respond to long presses.
  **Rename** An input box pops up, which contains the current title (select the entire paragraph).
  Remove the blanks at both ends and save. The empty ones cannot be saved; write `.title`
  (`ConversationStore.rename`, change regardless of whether there is a title). The created title is
  only written when there is no title yet (`setTitle`), so even if the title is set late in the
  background, it cannot cover the name changed by the user; the sidebar and the "Continue "..."
  Chat" on the top of the floating capsule card are all replaced with the new name
  `ConversationStore.changes`. **Pinned** Write `.pin` (pinned time, `ConversationStore.setPinned`),
  cancel pinned to delete it; the conversation list contains pinned time
  (`ConversationSummary.pinnedAt`), the modified time of the conversation is not changed, and it
  returns to the original position in "Recent" after canceling; there is a small thumbtack in front
  of the pinned title. Deleted conversations will no longer be renamed or pinned to the top.
  **Delete** pops up to confirm "Delete this conversation?" and indicate the title of the
  conversation; It indicates that tasks will be stopped when running or queuing, and when bound to
  scheduled tasks, it indicates that several items will be deleted together; the red words on the
  right are "Delete" and the left are "Cancel" (the same as the confirmation of deleting things
  elsewhere). When the conversation cannot be changed (the task is in progress and parallelism is
  not enabled), it cannot be opened. However, it still works if you press and hold it (the
  instructions are written above the first saved conversation). After confirmation, it will no
  longer be listed on the sidebar (`ChatViewModel.deleted`). What is deleted is that the dialogue on
  the screen will be replaced with a new empty dialogue, and the task will no longer be followed;
  The rest is done by `ConversationDeleter` (`app/chat/ConversationDeleter.kt`) in the background:
  first delete `trigger.threadId`, which is its scheduled task (preventing them from starting new
  tasks), then use `TaskService.cancel` to stop all its running and queued tasks and wait for it to
  finish (up to 15 seconds, the "stopped" round saved at the end is also within the deletion range),
  and then delete its workspace (attached) `inbox/`, the screen sent by the user `screens/`, the
  file written by the task; Delete by handle, do not follow the link, hold the sharing permission of
  the tree lock, and reset the mutex), and finally delete the session file
  `ConversationStore.delete`, `.title`, `.pin`, half-written temporary files, and damaged files
  moved aside. The supplementary writing that comes later (the title created in the background, the
  ending and explanation after the closing) will not be written back; if someone adds another round
  to this number, it will be considered as starting over, and the side column will be relisted.
  Running records (running processes, running statistics) are global and will not be deleted; files
  exported to the download directory belong to the user and will not be deleted.
- **Conversation page**(`app/ui/ChatScreen.kt`, text in`app/ui/ChatCopy.kt`): The top bar is "Mina"
  and what you are doing at the moment (which app is being operated / waiting for you to handle),
  and a new conversation in the upper right corner. The empty conversation is a greeting and a list
  of examples by time period, which can be sent directly after clicking. The task in progress is a
  line of status (circle, current progress, time) plus a vertical line step (a small arrow on the
  right side of the step with specific actions. Click on it to list the things to be done in this
  step one by one under the line: the interface statement of the action · object · result is the
  same as the running process page.`Exchange.stepActions`; does not show the tool name and error
  code), when operating the phone in the background, there is a line of small words below; when you
  are asked to process (take over, question, confirm, pause, lock screen, blocked), the progress
  stops at the current step (orange dot, "paused"), and there is a white card below to write down
  what you are asked to do and the button. After the process is completed, the process will be
  harvested with a line of "N steps taken · time taken · view process" (enter the timeline). The
  answers are arranged in Markdown on a very light gray background (lower left corner, symmetrical
  with the user's blue bubble). Below are the "source" capsule (click to see the original text and
  screen) and the file card. Long press a message (a completed answer, a reply that has been
  finished before interjecting, an explanation after stopping, or the user's own blue bubble;
  vibrate to see the "message operation" in the accessibility) and an in-place menu that looks the
  same as the sidebar will pop up (`ChatCopy.actionLine`, see the sidebar below; the dialogue page
  is in`LongPressMenu()`): The back is darkened (it’s still blurry since Android 12), and the
  message is raised into a shadowed one (the answer is a white card, and the user’s words are still
  blue bubbles; a very long answer only raises the first half of the screen), below it is “copy”,
  “quote” and “select text”, the user’s words are to the right, and the menu is also to the right.
  **Copy** Put the entire plain text into the clipboard and prompt "Copied": Answer and remove the
  Markdown symbol (`app/ui/MessageMenu.kt`: Paragraphs, titles, and codes should be as they are in
  the original text. Each line of the list should be marked with "•" or "1.". Each line of the list
  should be marked with ">". Table grids should be separated by tabs (no separators). Remove
  attachments, screens, and quoted lines from the user's words; the above "Failed to Verify" prompt,
  source capsules, file cards, photo grids, and viewing processes are not counted. **Select text**
  Let that one be changed back`MarkdownModel.toPlainText`(A reminder: "Long press the text to
  select"): Press and hold again to see the system's selection handle and copy bar. You can select
  only a paragraph; it will remain like this until you change the conversation, and other messages
  will still pop up the menu. The part where the answer is growing or interrupted by the
  interruption cannot be selected without popping up the menu. The selection handle is in a separate
  pop-up window, and dragging the handle will not bring out the sidebar; the movement after long
  pressing and dragging is consumed by the selection, the horizontal swiping of the sidebar and the
  vertical scrolling of the list will not follow, and the horizontal swiping and vertical scrolling
  without long pressing will still be the same. Click the link in the answer as usual. **Quote** Put
  the plain text of that article into a small strip above the input field (a blue line on the left,
  up to two lines, × "Remove Quote" on the right,`SelectionContainer`), it will be sent with the
  next sentence, and the bar will disappear after it is sent; it will be cleared when changing
  conversations or starting a new conversation; it will not be sent when there is only a quote but
  no words (it is OK when attaching it). The quote is written at the beginning of the user's
  sentence (`ChatUi.quote`): The first line indicates who is
  quoting,`core/agent/QuoteMessage.kt`(Mina's answer) or`> [Quoting your earlier reply]`(The user's
  own words), then each line begins with ">" and is the quoted content (up to 1000 words, cut off
  more and add "..."), followed by a blank line is what is said this time, and the attachment line
  is continued at the end as before. Save this sentence as it is`> [Quoting my earlier
  message]`(Interjection into`Turn.userText`), hand over the task, and do not add new fields: new
  tasks, interrupting (the task is running), and answering questions are all the same. The model
  directly sees what is quoted, and the text of the previous round is also included; if there is no
  such line in the old dialogue, it is displayed as it is. On the conversation page, draw the
  quotation as a small area of light color (maximum two lines) above the bubble. This sentence
  should be called without quotation in the conversation title, sidebar, and notifications
  (`Turn.steers`). The card of the floating capsule does not do these things: it is in a
  barrier-free floating window and does not usually grab the focus. The selection handle and system
  copy bar cannot be removed there or are unreliable; the sentence on top of it calling the task
  also has no reference. The input field is a glass capsule. You can enter as usual during the task
  as an interruption (gray text in the input box). The rounds on the screen start
  with`ChatCopy.plainTitle`Whichever one is saved shall prevail: whichever dialogue it has written
  is in`ScreenCopy.plain`Send the number of that dialogue, no matter who wrote it (here, suspended
  capsule, timed task);`ConversationStore`When you receive the dialogue on the screen, reread it,
  merge it with the rounds you have followed here according to the running number (use the copy
  here, with steps and times, no repetition), leave the question and answer you are following at the
  end; when the sidebar is open, also reread the dialogue list with it. When the conversation page
  is visible again (return to the front desk, come back from other
  pages,`ConversationStore.changes`) and add it again, and connect the tasks that were started
  elsewhere in this conversation and are not being followed here (in the original words, use the
  task itself, and if it is inserted elsewhere, it will also be hung up); there is no need to wait
  to return to the front desk, and the tasks started elsewhere in this conversation on the screen
  will be connected as soon as there is a state, and the words and steps will be there when running.
  The service registers the task in the task list (`publishRuns`) before showing it (`TaskRun.show`),
  so the original words are already available when the UI receives the status; if they are not
  available yet when attaching, they are filled in as soon as the task list updates. With the
  parallel switch off, the UI also identifies tasks by the conversation id carried in the status, so
  tasks from other conversations are not attached to the conversation on screen; when the service
  switches to showing a task that just started in another conversation, the task being followed here
  is shown again by the service.
- **Attachments to the dialogue page** (`app/ui/ChatAttachments.kt`, `AttachSources.kt`, pure logic
  is in `AttachmentMessage.kt`, attachment line is written in `core/agent/AttachmentLines.kt`):
  Click the "+" on the left side of the input field to open the panel below the input field, which
  occupies the keyboard position and is as high as the keyboard (use the default height if you have
  not seen the keyboard). Open the panel to close the keyboard, and then click "+" (convert to ×) or
  click the input box to close it and switch to the keyboard. When the two are swapped, the input
  field will not jump up and down; return to the close panel first. There is a row of cameras on the
  top of the panel (the system camera takes pictures, and the photos are first written to `camera/`
  in the cache, no camera permissions are required), albums (system photo selector, no permissions
  are required), files (system file selector), and the four columns below are "recent photos" (newly
  added photos in the media library are first, and thumbnails are read in the background according
  to grid size). The permission to read photos is asked once when opening the panel for the first
  time (photos can be read from Android 13 onwards, only some photos can be given since Android 14,
  and external storage can be read from Android 12 and earlier); when it is not given, there is a
  description and "Allow access to photos" (go to the App information when you select not to ask
  again). When it is only partly given, the given permission is listed, and the range can be changed
  next to it. The selected photos have serial numbers, and there can be up to 9 photos and files
  together, hanging in a row above the input field (photo thumbnails, file cards, × removed); you
  can send if there are words or attachments, or you can send only attachments. Copy them into the
  conversation workspace `/workspace/inbox/` (`Attachments.importInto`, each no more than 200 MB as
  before) when sending. Each line at the end of the message "Attachment: <path> (<size>)" is handed over
  to the task - the model still gets these lines of path, and the picture is still given to the
  picture by the sandbox file reading tool; the attachment needs to be sandboxed, and there is no
  "+" when the device does not support sandbox. According to these lines, the conversation page
  draws the user's messages into a photo grid (one large picture that maintains the proportion, two
  pictures side by side, three pictures in three columns) and file cards (the type corner, name, and
  size are colored according to the extension). When writing, a new bubble is opened, and the lines
  of the path are not displayed; the messages in the old conversation are also drawn in the same
  way. When clicking a photo or card, copy a copy from the workspace (via the file gate) to
  `viewing/` in the cache, and hand it to the file viewing page (pictures can be zoomed), and hand
  the unseen types to other apps; these two cache directories are given content:// addresses by
  `AttachFilesProvider`, and are not exported to the outside world. They are temporarily authorized
  when used, and the workspace itself is not open. The conversation title and the message with only
  attachments in the side column are called "photos", "3 photos", "contract.pdf" and the like
  (`ChatCopy.plainTitle`). The Suspended Capsule card remains unchanged.
- **Camera declaration and refresh rate** (`AndroidManifest.xml`): The camera accessory is the
  system camera. It is declared in the manifest that the camera is used, but it is not required
  (`required="false"`). Devices without cameras are installed as usual. Some systems classify
  applications according to APK characteristics. If not declared, Mina may be regarded as a game and
  the refresh rate may be limited, so this statement should be kept, and `ManifestProvidersTest`
  will guard it.
- **File View Page** (`app/ui/viewer/FileViewerActivity.kt`, the interface is in
  `FileViewerScreen.kt`): Click the file card in the conversation to view it here. A single Activity
  can also be opened with `FileViewer.open` elsewhere. The type first looks at the MIME given by the
  content provider, and then looks at the extension (`ViewerKinds`, `app/ui/viewer/ViewerKind.kt`)
  if it is not recognized: the webpage uses a new WebView (script-opened, zoomable, does not go
  through the web browser); Markdown uses the same `MarkdownText` in the dialog; PDF uses the
  system's `PdfRenderer` Draw page by page according to the screen width, only the visible pages are
  drawn, you can zoom in and out with two fingers, and the page number is displayed at the bottom;
  the picture is spread proportionally and can be zoomed and dragged; the text is displayed in the
  same width and can be selected. Text and Markdown can read up to the first 1 MB. If it exceeds the
  limit, a sentence will appear saying "The file is large, only the first part is displayed." On the
  right side of the top bar are "Share" and "Open with other apps"; if you can't read it, say
  something and say "Open with other apps". Other types do not enter this page and are directly
  handed over to the system selection panel.
- **Small screen** (`app/ui/AgentPip.kt`): A small draggable floating window on the conversation
  page. It usually stops in the lower right corner and above the input field. It displays the
  virtual screen or web page that Mina is operating in real time (you can only watch the operation);
  the thin green border means that he is working, and the orange breathing light means that he needs
  you. It is a floating layer within the app, not system picture-in-picture. The webpage screen
  comes from the current tag hanging on the webpage display; when the webpage is only read in the
  background temporary tag (`web_read_page` with address), there is no tag on the webpage display.
  `TaskRun` and `WebPreview` are not included in the webpage (`webOpen` is false and the screen is
  not placed). The small window does not appear because of this. The floating capsule is also
  displayed as a dialogue card. Once the visible page is opened, it will be displayed as usual. On
  the mobile phone, the creation of the virtual screen does not mean that there is a picture: the
  task screen that only uses system commands and event tools is empty, `TaskRun` is set when the
  target app is opened on the screen for the first time, `appShown`
  (`TaskEnvironment.agentAppShown`), and the small window appears by pressing
  `TaskEnvironment.previewOpen` (the app has been opened or the built-in browser is used), and the
  entire task will not be black. After expansion, "This task will no longer display the small
  screen" in the upper left corner (`ChatViewModel.closePip`, `PipUi.closed`): before the end of
  this task, there is only a small "Show Screen" button in the lower right corner, click it and open
  it again (`reopenPip`); clear it when changing to the next task or connecting to another task.
  When it is turned off, the takeover will not automatically expand when it is started; when you are
  asked to deal with it, the small button turns into an orange breathing "Waiting for you to deal
  with", click it and scroll to the card you want to deal with. There is no setting switch; I want
  to see all screens and go to the background screen page of the side bar.
- **Suspension Capsule**(`app/capsule/`): When the user is in another app, a Mina icon floats in the
  upper right corner of the screen and below the status bar, telling you the most important thing in
  the background at the moment; it is usually just a capsule (about 40dp), which expands into a line
  of text when it has something to tell you. Accessible service paintings on the floating floor
  (`app/cap/phone/a11y/OverlayHost.kt`: Accessible floating layer window, do not have "display on
  top of other applications" permission; equip Compose with life cycle, state saving and ViewModel
  host outside of Activity; usually do not grab the focus, but can be switched to receive the
  keyboard when typing; when the service is disconnected`ScreenAccessibility`Remove the window it
  added; if it cannot be added, it will return empty and no error will be reported). Show what is
  shown by pure logic`CapsuleModel`Fixed: The switch is off, the accessibility service is not
  available or the overlay cannot be drawn, Mina's own interface is in the foreground
  (`AppForeground`number of visible activities), the screen is off or locked (`DeviceStateWatcher`);
  otherwise it only says one thing according to the priority - you are working on it (the App has
  been moved to the home screen, with a line "You handle it · Don't do it · Processed" in the middle
  of the top, and the two buttons are to cancel the task and return it), you need to take
  over/agree/answer, waiting for conditions, working, done/not done/stopped. The status on the
  stone: when working, a blue arc in the outer circle rotates (it is a full circle when the
  animation is turned off), a gray circle for waiting conditions, and the whole stone turns orange
  when you are asked to handle it, the outer circle exhales and inhales, the number of items is
  written in the corner when there are several tasks, green ticks are completed, and gray corners
  are marked for not being completed and stopped. There is nothing when idle ("always displayed" and
  no tasks); when you change a step during work, it will not start. There are only two times when it
  is expanded into a capsule: there is something for you to deal with, but you have not seen it in
  the card (it stays there, without shrinking or fading, until the card is opened or the thing no
  longer needs you; the few things you see when opening the card - the same thing, the same waiting
  - are recorded as seen, and then returned to the orange one, and a new one comes or another waits
  before expanding); It disappears after 10 seconds, and returns to the icon when "always
  displayed"; what you have seen in the card is directly a star; when the conversation is deleted,
  the one it just ended disappears immediately, and the card no longer continues it). When there are
  several things, write "N items in total" on the capsule. If there are things that you need to deal
  with, write "1 item that you need to deal with · 2 items in total"; use the text
  first.`Running.narration`,reuse`reason`, playback and batch execution are written as "Follow the
  route 3/7" (when reading the screen, the saying is`CapsuleCopy.describe`, such as "Mina is
  working:..., click to see details"). Each sentence in the same thing and in the same state should
  be paused for at least 1.5 seconds (only the last sentence is left during this period). The
  capsule will only become wider but not narrower. It will only be re-set when the state is changed,
  expanded or retracted; the maximum width of a line is 60% of the screen. The capsule and the
  capsule have the same base, and the width slowly changes between the two. The icon stays on the
  side where it is docked (change it directly when the animation is turned off).`CapsuleContent`It
  is a capsule interface without state.`CapsuleController`Connect task status, switches, front desk,
  screen and services to the model, and add, move and remove windows according to the results. Click
  one or the capsule to expand the card (`CapsuleCardContent.kt`, without state; content consists of
  pure logic`CapsuleCards`Set, see`app/capsule/CapsuleCardModel.kt`): The card only says one thing,
  there is no list, and there is no switching in the card: the task of the conversation that has
  just been sent in the card is first (so that you can see the answer), otherwise the tasks that you
  need to deal with (take over, agree, answer), in progress, and just ended are taken first; there
  are other things at the same time, go to Mina's dialogue page to see. The selected item is
  selected according to its current status (do not click on the past): it is not over yet, and it is
  currently operating an app or web page (it has its own virtual screen).`TaskRun.displayId`, or
  using the built-in browser`TaskRun.webOpen`) are task cards, others - pure chats, those that have
  ended (including those who have operated the app and responded to the call after completing the
  task), and when you are free - are all conversation cards (`CapsuleCards.taskCard`). The tasks in
  the task card are the most recent three steps (the steps are the same as those on the dialogue
  page.`ChatViewModel.reduce`), time used; the task is still in progress (working, Mina When asking
  you, waiting for conditions), stop is the black stop button on the right side of the input box
  like the input field on the dialogue page; why do you want to take over (list the goods, amount,
  and address when paying), I will handle it (bring the number you are waiting for this time, the
  same as the one on the notice), stop, when you are behind other takeovers, write "First handle the
  takeover of the other item, and then deal with this one." If you don't let me handle it, if you
  are at the front and behind, write "After handling this, there are N Files are waiting for you"
  (the ones read one after another are shared by several tasks)`TakeoverQueue`, read it again as
  soon as the queue changes; among the items that are equally important, mention the ones at the
  front of the queue first, and the next item will immediately become "Need You" on the capsule
  after the previous item is returned); you are required to agree to the same set of words and
  declaration number as the confirmation card on the dialogue page, agree/disagree, and "allow this
  task" and "allow in the future", and the bottom line operation is only for "agree only this time";
  Mina When asked, if you give the option, list it as a button (as an answer); wait for the
  conditions to say why, and continue if you can continue; and at the bottom is "Go back to Mina to
  see the complete process." Conversation cards are the back and forth of that conversation
  (`app/capsule/CapsuleThread.kt`of`CapsuleThreads`Row,`LazyColumn`Drawing): the same record as the
  conversation page (`ConversationStore`The recorded turns are followed by the unrecorded tasks at
  this moment in this conversation (delegate by running record number). What you said is the small
  bubble on the right. Mina’s answer is the full text with the mark removed
  (`CapsuleCards.answerText`), the old one is on top; the reply interrupted by the interruption is
  arranged in front of the interruption like the conversation page, with a light color and a line
  below "Interrupted by your new message"; the reply that has been finished before the interruption
  is also arranged in front of it, as the answer looks like; the interruption that has not yet been
  handed over to the model is arranged after the answer being written; the round of operating the
  app or web page (read its active objects from the running record of that round, and the records
  that have been cleared are treated as pure chat) only receive one line "✓ Shopping · "It's done"
  "12306 · Not done" and then answer the question without putting the steps, pictures and sources;
  in pure chat, the words will grow when you answer. There is a stop button on the right side of the
  input box. When you answer and agree, the question is in the back and forth, and the button is
  fixed at the bottom. The last 10 rounds are sorted first, then scrolled to the top and then 10
  rounds forward (each key is determined by the running record number, and the list stops at the
  same place); when opening, the latest sentence you said stops at the top of the visible area, and
  the words that go down only follow you down when you are at the bottom (same as the conversation
  page). Below is the input box and "Return to Mina". All buttons go`TaskService`Existing actions
  (cancel, continue, take over, answer, agree, disagree), with the task number of that thing;
  "Return to Mina" is the same entry as the click notification`TaskService.openAppIntent`. Click
  outside the card, close the button or go back to retract it into one (it is a capsule when there
  are still things you have not seen yet that you need to deal with); when it is unfolded, the model
  stops the watch, and the result the user is looking at will not be taken away. After it is put
  away, the remaining time is continued; when the App is handed over to you, the card is put away
  and replaced with the one at the top. The card and the card are each in a floating window, waiting
  for their respective positions (the card is empty when folded and has no size): when expanded, the
  card zooms in and fades in from the position of the card in its own window (about 240
  milliseconds; wait for the cards to be arranged before starting, before starting, the card is
  still in the same place), and the window of the card becomes empty; when the card is retracted,
  the card retracts to the position of the card, fades out, and the card pops up on the edge at the
  same time (all in`app/capsule/CapsuleCardContent.kt`In the same animation, only the layer is
  moved, and it goes directly to the position when the animation is turned off). Do not allow a
  window to move back and forth between two places, because Android will animate the simple movement
  of the window into a sliding animation; nor change the window size frame by frame (every time the
  window is changed, it must be rearranged and the canvas is changed, one card at a time). The width
  between the capsule and the capsule only changes during drawing: layout it to the wider one
  between the two, and then shrink it after drawing. When I am working, I want you to take over, and
  when the conditions are met, a small virtual screen will appear on the left (that is my
  own`TaskRun.frames`: The mobile phone is a frame of the virtual screen, and the web page is a
  frame of the built-in browser, which is the same source as the picture-in-picture in the
  application; tasks without pictures will not be released), and will only be collected when the
  card is expanded and looking at that thing, at most 5 pictures per second, and the same as the
  previous one will not be pushed (`previewFrames`,exist`app/capsule/CapsulePreview.kt`); Click to
  enlarge, click again or click outside to return. The capsule can be dragged (`OverlayHost`The
  window is arranged according to the entire screen and is only as large as the content. Drag on the
  root of the window according to screen coordinates. It is not dragged until it exceeds the touch
  threshold. The button pressed will be canceled and will not be regarded as a click.), let go and
  paste to the nearest left or right (12dp away from the edge, avoid the status bar, bangs and
  bottom gesture bar, the range is`OverlayHost.area`(Read the system column); which side to stop and
  the proportion of the height to the swingable range are recorded in`FeatureFlags`, and put it back
  in proportion next time and after the screen is turned; the "You handle it" is in the center of
  the top and can only be dragged up and down. The width of the card remains unchanged and the left
  and right are centered. The height of the dialogue card is fixed and does not change with the
  content: 58% of the screen height. When the input method pops up, it is placed above it (the
  middle section becomes shorter, but the input box is still visible); the task card is as high as
  the content, and no space is left below. The middle section takes the height of the small picture
  on the left (according to the aspect ratio of the picture) and the text on the right. When there
  is no picture, it is as high as the text on the right, and the highest is the same as the dialogue
  card. Longer text rolls in the section on the right. If the two cards cannot fit in the middle,
  they are rolled inside, and the buttons and input boxes are fixed below. The position of the card
  is recorded as above: the proportion of the upper part in the placeable range (the range is
  calculated based on the tallest card, and cards with different heights are aligned at the same
  proportion), the upper part does not move when the card is changed (task cards and dialogue cards
  are exchanged) or the content becomes taller or shorter, and it is in place in one fell swoop (the
  window only changes once with the size of the content, not frame by frame). When it cannot be
  placed down (gesture bar, input method), move it up until it is just right. Drag the card up and
  down according to the title bar (`OverlayWindow.draggable`Give a handle, you can drag it only when
  you press it, and the up and down slide in the middle is scrolling; it is also dragging when you
  move it past the touch threshold, and the close button will not be clicked). Let go and clamp it
  within the status bar, bangs and gesture bar. The above proportions are recorded
  separately.`FeatureFlags`(In the past, the ratio of the fixed height card was exactly the ratio
  above it, so it can still be read). Next time you expand and rotate the screen, press it to move
  it back. Algorithms are pure
  functions`CapsuleDocking`and`CardDocking`(exist`app/capsule/CapsuleDock.kt`). When reading the
  screen, there is an additional "Move to the other side of the screen" function on the capsule
  instead of dragging. The buttons, small screens, folding and returning of the card have
  instructions. There is an input box at the bottom of the card (not available when you are asked to
  take over or agree): when running and waiting for conditions, it is "Supplement or change the
  request...", and leave it to that thing (the same way as inserting during the dialogue page
  task,`TaskService.start`Bring the number of this sentence); when Mina asks you, it is "Answer
  Mina..." (the option button remains); after the completion, it is "Continue..." and continue in
  the same conversation; when there is no task (the switch is "Always display"), click the Mina icon
  to see an idle conversation card: continue the conversation the user is currently in the App
  (`ConversationStore.current`,Depend on`ChatViewModel`When opening a new conversation, switching
  conversations, or speaking in a new conversation, choose Pure Logic`CapsuleIdle`Here: When there
  is no record, continue the most recent one. The newly opened dialogue that has not yet been spoken
  is a new dialogue; the dialogue it is in has priority over the results of other dialogues that
  have ended and are still pending); top "Continue «<latest conversation title>»" and "Chat · New Conversation", in the middle
  is the back and forth of that conversation (it is empty when it is a new conversation), and the
  input box is "What do you want Mina to do?". The latter two are the same as sending a task in the
  conversation page (recorded in that conversation; other tasks are queued during running and will
  not be lost). After that, the card follows the task of that conversation, and the answer grows in
  the conversation card; when the app is opened, it is replaced by a task card. The input box is
  usually just a line of prompts. Clicking it allows the floating layer to receive the keyboard
  (`OverlayWindow.setFocusable`), send, close, click outside, and return immediately when pressing
  return; get out of the way when the input method pops up (the position of the input method window
  is determined by`OverlayHost.imeTop`Read from the accessible window list, re-read as soon as the
  window changes, no matter which app the input method is played against): the cards, bullets and
  expanded capsules are moved to it, and the input method is retracted and returned to its original
  position. If you don't touch it for 3 seconds, one will fade to about 40% (the outer ring and
  corner markers remain solid color), and it will be restored immediately if it is touched, dragged,
  or the status or number of items changes; the unfolded capsule requires you to handle it, and the
  "You handle it" item does not fade with the card (the rule is pure logic`CapsuleFade`). When
  turning the screen, reposition according to the noted side and height ratio; no special treatment
  is required when playing in full screen. The switch is in Settings › "Suspension Capsule"
  (in`app/ui/SettingsSubPages.kt`): Close / Show when there is a task / Always show (default, you
  can double-click the capsule to display this screen at any time), exists`FeatureFlags`; For those
  that have not been selected (including those upgraded from old versions), press the default.

- **Screen at the moment** (double-click the capsule): Double-click a screen or capsule to send the
  screen the user is looking at at the moment to the conversation in the card that will be expanded
  by clicking; then it is exactly the same as sending a sentence in the card (interject while
  running, answer when Mina asks you, continue talking after finishing, continue the latest
  conversation or open a new conversation when free, the same `CapsuleController.submit`), and then
  expand the card to see the answer. Clicking will not expand until Android's double-click time
  limit (`ViewConfiguration.getDoubleTapTimeout`) passes. The judgment of single-clicking and
  double-clicking is purely logical `DoubleTap`; there is also "send the current screen to Mina" in
  the screen reading software. When the card has no input box (requiring you to accept and agree),
  there is no place to send it. Double-clicking is the same as clicking. Displaying or disabling the
  capsule still follows the switch of the floating capsule, and there is no other switch; the
  capsule is not there when Mina is in the foreground or locks the screen. There is a screenshot
  button on the left side of the card input box: take a screenshot and hang it on the input box
  (thumbnail, "your screen at the moment", App · time, × do not want), you can add a sentence and
  then send it, just sending it is the same as double-clicking it. Screenshot: Do not dismantle the
  floating layer window, only hide the content inside (`OverlayWindow.hiddenWhile`), wait until the
  hidden frame is drawn and handed over to the system (frame submission callback), and then two
  refreshes (the synthesizer replaces it) before taking a screenshot; When the input box of the card
  is open, close it first and wait for the input method to exit. Use the accessibility service
  `takeScreenshot` to take a screenshot of the home screen
  (`ScreenAccessibility.takeMainScreenshot`, if it is too fast, wait for one second before taking
  the screenshot). After taking the screenshot, immediately display the overlay back and flash it
  up. At the same time, read the accessibility tree of the App window on the home screen (status
  bar, navigation bar, input method, overlay are not counted). Organize in the background
  (`MainScreenCapture.build`): The words on the screen are first used with the elements with names
  in the tree. If the tree is not enough (the same set as the task reading screen `TreeAdequacy`),
  recognize the words on the screen (`PhoneOcr`), only fill in the words that are not in the tree,
  remove the wrongly recognized lines and the words in the status bar and navigation bar; It’s just
  that the elements that connect several paragraphs of text inside the frame (the entire row of a
  row of setting items) are not in separate columns, leaving the paragraphs inside; Each row has a
  center point (0-999, the pixel center of the element in the tree is also converted), from top to
  bottom, and from left to right in the same row (`core/userscreen/UserScreenText.kt`). Pages that
  prevent screenshots (the screenshot reports a safe window error, or the screenshot is almost
  completely black with an App window on the screen) do not leave the screen, just note which app it
  is from, and the screenshot cannot be taken. Mina herself was at the front desk at all times.
  Storage: Follow the conversation and put it in the workspace of that conversation
  (`/workspace/screens/<id>.jpg` and `.json`, `app/chat/UserScreenFiles.kt`, with the permission of
  the workspace file gate to read and write, and hold the tree lock when writing), in the same place
  as the attachment, and disappear when the workspace is deleted; the command in the sandbox can
  also read this picture. The message itself is an ordinary sentence: a single line
  `[[screen:<id>]]`, and the rest is a postscript (`core/userscreen/UserScreenMessage.kt`). It is
  saved into the conversation as usual, as an interruption or reply. The picture selected from the
  album or file is still on the attachment line, and the two are not mixed. Click it to draw the
  conversation page and card into a screenshot bubble (`app/ui/ScreenMessage.kt`: thumbnail, "your
  current screen · app · moment", add a new bubble as a postscript; What prevents screenshots is the
  line "not captured"), which is called postscript or "your current screen" (`ScreenCopy.plain`) in
  conversation titles, sidebars, and notifications. For the model (`core/agent/UserScreenTurns.kt`,
  the text is in `UserScreenPrompt` and `user_screen.*` of `feedback/loop.md`): This sentence is
  replaced with "The user just took a screenshot of the screen (App, time) he is looking at, without
  postscript / postscript:..." (if the screenshot is not allowed, tell the truth that it was not
  taken). When the main model can see the picture, the screenshot will be connected to this
  sentence; Follow a paragraph of `user_screen` context (not included in the budget, retained when
  compressing): number, app and package name, time, words on the screen (set with untrustworthy
  mark), and if there is no postscript, bring the rule of guessing the intention by pressing the
  screen - just answer, read, and check and do it directly; if you want to write or change something
  elsewhere (note, issue, publish, subscribe, pay, delete, change settings) first use ask_user to
  ask once, give options (buttons in the card), and then do it after agreeing; If you don’t know
  which app the user uses to do this kind of thing, ask them, and write it down if you can remember
  it. If there is a postscript, proceed as usual, without additional rules; Confirmation of
  irreversible operations shall remain as usual. When only the screen is displayed and no words are
  spoken, the intention is not judged (there are no words to judge). In the previous rounds, there
  was only one line left on the screen (App, time, the first few words on the screen and the
  number). If you want to see it again, use `view_user_screen`: press the number to the App, time
  and word. When the main model can see the picture, a screenshot will be attached. If you can't see
  the picture, you will be asked to ask questions about the saved screenshot of the picture model
  (character prompt word `user_screen`); this time or in the previous rounds, it will only be
  provided with the screen. The words on the screen are recorded in the evidence ledger (answer
  source); Judge the ending, retrieve the memory and read the changed sentence; if you have a
  screen, you will not remember the experience route.
- **Notification**: There are notifications when the task is in progress, needs to be taken over,
  needs to be answered, and the result; the takeover button is on the notification. The two channels
  "Need Your Handling" and "Task Result" are set to high importance (pop-up), and "Task Progress"
  remains quiet; When the capsule is displayed on the bright screen, "Need You" will be sent as
  usual but enter the notification bar quietly (use the system's group reminder rules: put this into
  a group without summary, only let summary reminder, the system will not sound or pop up the
  banner, the same method as the system's own silent notification; no channel change, no new
  channel), it will still pop up when the screen is locked, the capsule is closed or cannot draw; As
  a result, the channel id was changed (`task_result_v2`, the old one was deleted at startup), and
  channels were created everywhere with `TaskService.ensureChannels`. Status monitoring checks
  whether these two channels have been lowered. If they are lowered, it will prompt. The repair
  button directly opens the system settings page of the channel. If it cannot be opened, it will
  return to the application notification page. When the mobile phone operation is started, it will
  also be checked after notifications are allowed. Settings › "Notifications" page (in
  `app/ui/SettingsSubPages.kt`) only looks at the status, go to the system to change it, and do not
  put the notification switch in Mina: the top is the notification master switch ("turn it on" when
  not allowed, Android 13 first requests permission, otherwise it opens the application notification
  page), below there is one line for each of the three channels "Needs You to Handle", "Task Result"
  and "Task Progress", indicating when it was sent and whether it will "pop up", "only enter the
  notification bar" or "closed" now. Click a line to open the system settings page of the channel
  (the same set of jumps and downgrades as above). The status judgment is a pure function
  `NotificationOverview.of` (`app/settings/NotificationOverview.kt`): according to the channel
  importance, `IMPORTANCE_NONE` is closed, if it is not lower than `IMPORTANCE_HIGH`, it will pop
  up, and the rest will only go into the notification bar; "Need you to handle" and "Task Result"
  will be marked instead of popping up (the progress is quiet or closed is not abnormal), and when
  the main switch is turned off, all three lines are closed; The right side of the "Notifications"
  row in "Basic" on the home page displays the overall status (normal / N items will not pop up /
  closed). The "Notifications" row on the mobile phone operation page also enters this page. The
  channel importance is read from `SystemStatusMonitor` into `SystemStatus`, and updated with
  `refresh` when returned from system settings.
- **Other pages**: Settings (`app/ui/SettingsScreen.kt`): There is a general status card on the top
  (follow `Prerequisites.check`, everything is ready when everything is normal, and problems will
  become prompts with repair buttons), the following four groups - basic (model entrance and current
  main model, Token usage, mobile phone operation, application access, work, suspension capsule,
  notification), privacy and data (memory and skills, website login), advanced (files and computing
  environment, Memorized operating routes, Recovery and Repair, Developer Options), About Mina (when
  there is a new version, write "x can be updated" with a red dot). Clicking on each item is a
  sub-page (mobile phone operation in `app/ui/SettingsSubPages.kt`, work, suspended capsule, file
  and computing environment, memorized operation route, recovery and repair, and
  `DeveloperOptionsScreen`; switch by the settings page; go to the model page and configuration
  model page `NavStack`); the "Restore and Repair" page is the three authorization switches for
  mobile phone operation (open by default, toggle to execute directly, no confirmation, instructions
  are written below the switch; When the mobile phone has not been configured for operation, the
  display will open and prompts to complete the settings first), and the corresponding entrances for
  unsupported systems (lower than Android 13) and devices that are not supported by the sandbox will
  not be displayed. The engineering switch (`app/settings/FeatureFlags.kt`) is in the developer
  options, divided into two groups: "speed" (read the element first, look at the picture when
  necessary, bring back the screen change after the operation, do a few steps in a row when you are
  sure, directly open the page you want to go to, simple steps are handed over to the lightweight
  model) and "experiment" (do as you did last time, different conversations are conducted at the
  same time, and the summary of long tasks is written in strips). The developer options are only
  displayed in the debug version and performance test version. Among them, you can set the switch
  and storage limit of detailed Trace; After first installation or upgrade, turn on "Read elements
  first, look at pictures if necessary", "Bring back screen changes after operation", "Do a few
  steps in succession when you are sure" (then keep it off if the user manually turns it off), and
  the rest are turned off by default; the release version also takes effect, but these switches
  cannot be seen in the settings. There are also memory and skills, plans, and mobile assistant
  setup wizards. The Memory and Skills page (`MemoryScreen`, `SkillsSection`) is divided into three
  sections: "Memory" (grouped by facts, preferences, and practices, click on a line to bring up the
  panel: change, pin, delete), "Skills" (switch to activate and deactivate), "To be confirmed" (to
  be approved practices, new skill drafts, App knowledge drafts), the switch and all clear are
  placed at the end; The source page (`SourceScreen`) only displays screenshots of each source when
  there are screenshots (click to open the full screen to enlarge). If there are no screenshots (the
  web page text, files, command output, etc. read in the background), the original text excerpt is
  displayed, and the license page (`LicensesScreen`) uses the same set of top columns, groups, and
  rows as they do.
- **About & Check for Updates**:About Page(`app/ui/AboutScreen.kt`, text in`AboutCopy`) Go to the
  Settings homepage or "About Mina" at the bottom of the sidebar ("Settings" is on the left at the
  bottom of the sidebar, "About Mina" is on the right, go`NavStack`of`Route.About`). Top version
  number, Beta mark (`BuildConfig.BETA`) and build number (`BuildConfig.GIT_HASH`, take the short
  hash of the current submission when compiling (dev when there is no git); check the updates and
  update records below; contact the author (the author's WeChat QR
  code`res/drawable-nodpi/wechat_qr.jpg`, please note Mina when adding friends on the panel, long
  press or "Save QR code" to save to the album Pictures/Mina; "Join X Group Chat"
  opens`MinaLinks.X_GROUP`;When the interface is in English, put the X group chat at the top and the
  WeChat code at the bottom.`AboutCopy.X_GROUP_FIRST`Judge according to the language actually
  displayed in the App), feedback questions (go to GitHub to ask questions, the question template is
  pre-filled with three lines of model, Android version, and Mina version; copy the diagnostic
  information; say it in WeChat or`Route.License`:License original
  text`assets/licenses/Apache-2.0.txt`, third-party components listed below). All external addresses
  are there`app/about/HarnessLinks.kt`. Check for updates (`app/about/UpdateChecker.kt`) does not
  have its own server, so it only asks about GitHub's releases/latest interface (no login, short
  timeout). If it cannot connect, is limited, or cannot read, it will not be found; every time you
  open the App (`MainActivity.onStart`) are checked quietly once, and only those that come
  consecutively are blocked (those that are less than two minutes after the last automatic check are
  not checked.`UpdateSchedule.MIN_GAP_MS`; Write down the time before starting the check, and those
  who come in at the same time will not check twice). There will be no prompt or banner for failure.
  The result will be prompted after manually clicking "Check for Updates". The version found
  exists`files/update-v1.json`, when it is newer than the current one, the sidebar, settings
  homepage, and about page will have a red dot; when there is an APK attachment, "Download updates"
  in the update panel will be handed over to the system DownloadManager to download in the
  background (`app/about/ApkUpdater.kt`): Only official repositories are
  accepted`releases/download/`HTTPS address under (`ApkIntegrity.requireOfficialUrl`), the file is
  placed in the exclusive directory external to this application`Download/updates/`, only one is
  left at the same time. The task number is retained so that you can continue to see the progress
  after restarting. It can be deleted and retried if it fails; the download notification is only
  displayed during the download (when the notification is completed, the installation package will
  be opened directly by bypassing the verification, so it is not retained). After the download is
  complete, click "Install Updates". You need to allow the installation of unknown applications for
  the first time. After authorization is returned, continue; before opening the system installer,
  check in sequence: the size and the SHA-256 (interface of the published attachment)`digest`fields,
  if they are qualified, reject if they do not match), the package name, version number is higher
  than the installed one, the signing certificate is consistent with the installed one (or the
  installed certificate is in the signature history of the new package); if it fails, delete the
  file and explain the reason (`ApkRejected`). The system installer rechecks the signature and asks
  for confirmation. After the update is installed, clear the remaining installation packages at next
  startup (`MinaApp`inside`dropInstalled`). Publishing page still opens without APK attachment.
- **Diagnostic Information**: The user actively clicks "Copy Diagnostic Information" (always there
  at the bottom of the boot page, and also in the error step when it fails; about page › fourth item
  of feedback issues) to collect it, first in the bottom panel (`app/ui/DiagnosticsSheet.kt`) is
  shown to the user. Click "Copy" to enter the clipboard, click "Share" to send it to the system
  sharing panel, and nothing will be sent automatically.
  collect(`app/about/DiagnosticsCollector.kt`) Read-only ready-made status: system status, status of
  the built-in assistant, and the original text of the most recent pairing or connection failure
  (`BuiltinShellEngine.lastError`), when looking for the connection port, each source gave several
  candidates, when scanning the machine, how many ports are open, such as adbd, and whether there
  are ports filled in by the user (`AdbConnectPort`report
  to`BuiltinShellManager.lastConnectCandidates`, only the number, do not write the address and port
  number), automatically restore those items, model configuration, running summary, check for
  updates; the steps on the guide page use the input of the guide page itself (`setupInputs`,
  including steps confirmed by the user) to the same`PhoneSetupFlow`Count it, don’t count it again.
  written in text (`app/about/Diagnostics.kt`, pure function), the segment order is fixed, English
  keys: App, Device, Phone control, Recovery, Models, Recent runs, Update; the service provider only
  writes the interface type and host name, and only writes the relative time, ending, failure
  category, number of steps and model when running, and does not write the Key, login, task goal,
  answer and screen content; finally, the entire paragraph is desensitized (IP connection port, port
  number, email, "name=value" like credentials, and then handed over`Redactor`). The last paragraph
  of the GitHub issue template only prompts the user where to copy and paste, and does not
  automatically bring it along.
- **Compatibility Self-Check** (Settings › Recovery and Repair › Compatibility Self-Check,
  `app/ui/SelfCheckScreen.kt`): The user clicks "Start Self-Check", and it takes about a minute to
  check what can be used on the phone, and produce a report that can be copied and posted to GitHub.
  Pure logic in `core/selfcheck/`: Each item (`SelfCheck`: device, permissions, built-in assistant,
  virtual screen, opening app, screen reading, tapping and typing, continuous running, undisturbed
  home screen, built-in browser, sandbox, another app) is run in order. Each item indicates which
  items must be passed first. If one item fails or is skipped, if it depends on it, skip it and
  indicate which item is missing (partial use does not count as failed); Each item has its own time
  limit (5-25 seconds), and there is a total time limit of 90 seconds for the whole time. If the
  remaining time is used up, it will be marked as skipped; if an error occurs, it will be marked as
  failure, and the following items will not be interrupted; when canceling, both the running and
  following items will be marked as skipped, and `SelfCheckPort.release` (`SelfCheckRunner`) will
  always be called once in the end. The conclusion of each item is "status + one line of English
  facts + a kind of vernacular for the interface (`Advice`)"; The report (`SelfCheckReport`) only
  contains the version, model, system version, processor architecture, memory, screen size, whether
  there is wireless debugging and one line for each item, with an English key. The last paragraph is
  a desensitization of "copy diagnostic information" (`Diagnostics.sanitize`), and does not include
  account numbers, App lists, user content and serial numbers. The implementation on Android is
  `AndroidSelfCheck` (`app/selfcheck/`). The mobile phone uses the same set of tasks: the shared
  assistant connection in the process (`SharedShell`), `AgentDisplay` screen building,
  `AndroidScreen` opening page and `tapRef`, `typeText`, `scroll`, `back`. The result reflects the
  Agent real behavior. What is operated on the virtual screen is Mina's own test page
  `SelfCheckTargetActivity` (counting buttons, input boxes, scrollable columns, numbers that keep
  jumping and horizontal bars that change color back and forth, indicating that it is Mina's test
  page): Like the services connected to the computer, it is only open to the shell level: it is
  exported in the list, but the caller is required to hold INTERACT_ACROSS_USERS_FULL (available in
  the shell and system, not available in ordinary apps), no intent-filter, a single task, do not
  enter the latest task (`ManifestProvidersTest` guarded), When you open it on the home screen, turn
  it off immediately. `AndroidScreen.openOwnPage` passes its component to the assistant through
  `IHarnessShell.startSystemAction`. The assistant recognizes this component
  (`SystemIntents.OWN_PAGE_CLASS`, the package name must be Mina's) and directly starts it as a
  shell to Mina's screen (the same way as opening the App: startActivityAsUser with a startup
  screen, cannot return to `am start --display`); shell startup is not restricted by the Android
  background startup page, and Mina can be opened in the foreground or not. No new assistant
  interface is added. The system settings can only be opened by ordinary `openApp`, check and open
  on Mina's screen instead of on the main screen, and then return once and leave without clicking
  anything in it. Continue to run a test page and stare at the test page for 15 seconds: it will
  stop if the real-time image of the virtual screen does not change for 3 seconds, or if the jumping
  numbers in the control tree do not change for 3 seconds. The main screen is a task that compares
  the start of the run with the top task of the main screen of each node. The built-in browser
  creates an additional set of web page display and tag pool (no website list is given, and visits
  are not recorded in the website login). Open the local page
  `app/src/main/assets/selfcheck/page.html` packaged with the App (via WebViewAssetLoader, not
  connected to the Internet), read the page of `WebScreen`, and click on the number to check again.
  The sandbox only runs `echo ok` and one line of Python on arm64; the running environment is not
  ready and is partially available, and is not installed for self-test. Another app only counts the
  number of copies that can be used by another user (`appCopyUsers`, private space does not count),
  and the app is not listed. When you leave this page, it will be canceled. The virtual screen, test
  page, web page label and assistant connection will be released on the spot; if there are tasks
  running, they will not be started. After the run is completed, the entire report is displayed on
  the page, and "Copy Report" only copies; "Go to GitHub Feedback" to copy the report and open the
  new issue page, select the device feedback template `.github/ISSUE_TEMPLATE/device-report.yml`,
  only prefill the title (`[Compatibility] Android version model`), the report is pasted by the
  user, and the address is not included (the address has a maximum length). The complete test run on
  the real machine is `SelfCheckDeviceTest`.
- **First time getting started**: Welcome (`app/ui/WelcomeScreen.kt`) → Configure model → Mobile
  operation (`PhoneSetupScreen`, numbered list, can be skipped; after the connection is successful,
  the list will be retained, all checked, the top line "Already set up", the bottom main button
  "Start using", click to enter the dialogue, the entry from the settings is "Complete"). Which
  steps to take are judged by `Onboarding` (`app/ui/Onboarding.kt`) by pressing "Have you seen it
  before": only take the first two steps when there is no main model, and have not been configured
  with a model in the getting started process (those who have already configured a model will skip
  it directly); the mobile phone operation follows the original judgment (the system is new enough,
  not ready yet, and the boot has not been viewed).
- **Interface language** (`app/i18n/`): The interface text is all Android string resources, English
  is the default (`res/values/`), Simplified Chinese is in `res/values-zh/`, Traditional Chinese is
  in `res/values-b+zh+Hant/` (Android matches according to the text system, `values-zh` Not valid
  for Traditional Chinese systems), as well as Japanese, Korean, Spanish, Portuguese, Russian, and
  Indonesian (`values-ja`, `values-ko`, `values-es`, `values-pt`, `values-ru`, `values-in`;
  Indonesian follows Android’s old language code in). Each entry has a one-to-one correspondence
  with English (`StringResourcesTest` checks entries, placeholders, quantifiers to be written in the
  plural of each language, Traditional Chinese versus Simplified, and there is no Chinese left in
  English and languages that do not use Chinese characters), so the system displays corresponding
  translations when these languages are used, and English is displayed when the system is in other
  languages. Settings › "Language" can choose to follow the system or one of them (`AppLanguage`;
  each name is written in its own language, "Follow system" is written in the current language;
  Language tags are zh-Hans, zh-Hant, en, ja, ko, es, pt, ru, id. For languages other than Chinese,
  they are recognized by language code, regardless of region. The old code in given by Android
  Locale must be replaced with the current code before comparison. If the Chinese tag does not have
  a text system, press ICU's `ULocale.addLikelySubtags` to fill it out. Hant is Traditional Chinese,
  and other Chinese is Simplified. The zh-Hans saved by selecting "Chinese" in the past is
  Simplified Chinese): "Application Language" (`LocaleManager`) given to the system starting from
  Android 13, `android:localeConfig` lists these languages in the list, What can be changed in the
  system settings is the same value, and the system is responsible for remembering and rebuilding
  the interface); Android 11 and 12 are recorded locally, and `AppLanguage.wrap` is applied to the
  `attachBaseContext` of each Activity and the Compose host of the suspension capsule. After
  replacement, the current interface is rebuilt. `stringResource` is used directly in Compose; `tr`
  / `trPlural` (`app/i18n/UiText.kt`) is used for text calculation outside the interface (status
  copy, notification, task service, capsule data, tool description), which obtains resources
  according to the App language. In the JVM single test, it is replaced by `ResFileTexts` (test
  installation `ChineseTexts` for asserting Chinese) that directly reads the resource file. The
  quantity is written in plural resources, and the date, week, and month are written in
  `uiLocale()`. The tool statement uses the Chinese label in the tool declaration as the key to
  search the resource (`ToolLabels.TOOL_LABELS`). Only scroll through the interface: the built-in
  App knowledge, skills, and prompt words are still in Chinese; the answers, questions, and titles
  written by the model follow the language spoken by the user.

## 16. Voice input

Native Beanbao streaming speech recognition 2.0 can be configured separately on the speech-to-text
page, uploading PCM in real time and returning the intermediate full text; the credentials are
independently encrypted without going through the Ark chat model path. See
[doubao-asr.md](doubao-asr.md) for protocol, lifecycle and test restrictions.

There are two entrances to press and hold: the dialogue page input box and the floating capsule. The
two places only use `VoiceSource` (`voice/VoiceInput.kt`, `VoiceInput` in the App) to take the
session, and the interface test is replaced by a fake one; the logic of recognition and
transliteration is all in `app/voice/`, and the interface only cares about gestures, pictures and
connections.

- **`voice/` package responsibilities**: `SpeechChoice` is the user's choice (automatic, built-in
  with the phone, no use, a certain model of a certain service provider) and its access in the
  settings (`SpeechSettings`, one key and a piece of JSON, if it cannot be read out, it is
  automatic). `SpeechResolver` Combining choices, mobile phone capabilities (`SystemCapability`),
  and existing service providers, we came up with this engine `SpeechEngineSpec`. `SpeechSession` is
  a session interface, which only sends `SpeechEvent` (volume, intermediate results, text
  conversion, final results, failure); two implementations: `SystemSpeech` (mobile phone comes with
  built-in recognition, words are spoken while speaking) and `ProviderSpeech` (record with
  `PcmRecorder`, release the entire `Wav` to `Transcriber`). `HoldToTalk` is a press-and-talk state
  machine (press, slide up to cancel, release, end at the point), without touching the interface and
  gestures, and the input box and capsule are shared. When the hand is still pressed, the session
  will end by itself (it has built-in recognition that it will not restart after a sentence is said,
  and it will not be reopened after a mistake is made in the middle, but the word has been heard).
  The result will be kept first, and the interface will still display the word heard. When the
  finger leaves, it will be processed according to the landing point at that time: when it is
  stopped on Cancel, it will be discarded, otherwise it will be handed over, so when you want to
  cancel, the word will not be filled in or sent out; when you make an error while pressing, you
  will be prompted without waiting for release. Only when it is stopped on Cancel, it will be kept,
  and when it is released on Cancel, there will be no prompt. `Segments` puts together the segments
  in which the identification service is ended and reopened according to the segment. `Restarts`
  determines whether to reopen the empty segment and how long after that.
- **The engine is only selected in `SpeechResolver`**: The interface and `VoiceInput` do not decide
  which one to use. When "Auto" is selected (not selected), use the phone's built-in recognition if
  it is available; if the built-in system says it is, but it does not respond after trying it on
  this phone, give `Unavailable(SystemNoResponse)` and let the interface tell the truth and point to
  the setting, and you will not quietly switch to a service provider model that costs money**. If
  you select the service provider model, use it. The service provider is deleted and given to
  `Why.ProviderGone`. The various reasons for `Unavailable` are changed from the interface to a
  description of "Voice input is not yet available", and the button goes directly to the selection
  page in the settings.
- **The secret behind why the phone's built-in recognition does not respond**: The system can only
  tell you whether there is such a recognition service, and you will have to try it to find out if
  you ignore it. `SystemSpeech` 3 seconds after starting to listen (`NO_RESPONSE_MS`, leaving time
  to identify the cold start of the service process) there is no callback, this time it reports "no
  response" and ends; after waiting another 2 seconds (`FINAL_WAIT_MS`) there is still no final
  result, it ends with the same set of words: reply with the words you have heard, there is no
  callback, it means no response, it will not stop and end; the same session will only be recorded
  once if it does not respond. If it happens like this twice in a row
  (`SpeechSettings.SILENT_STRIKES`), it will be recorded as "no response"
  (`SpeechSettings.noteSystemSilent`, the number of times is also stored in the settings, and a
  stuck time is probably just a slow cold start). If there is a reply at any time in the middle, it
  will be counted again (`noteSystemAnswered`). After recording it, `SpeechResolver` no longer
  regards it as available, and the label on the settings page displays "Not Responding". When the
  user selects "Automatic" or "Included with the phone" in the settings, the marks and times are
  cleared. If the identification service is changed and the phone has updated the system, the user
  can try again.
- **Change another recognition service and try again**: The system's default recognition service may
  only be used by the system's own voice assistant, and may not provide any callbacks to other apps.
  When more than one recognition service is registered on the mobile phone
  (`SystemSpeech.services`), the default one is no reply, and the hand is still pressed, and the
  next one is called. The one open to other apps is tried first, and a maximum of
  `SystemSpeech.MAX_ATTEMPTS` can be tried at a time (`SystemSpeech.attempts`); the one that is
  called and tried is written down (`SpeechSettings.systemService`), and it is tried first next
  time. When there is only one service, it is the default one and will not be named again. The
  `[Speech]` section of the diagnostic information lists the options, the status of built-in
  recognition, and the recognition services registered on the phone (component name, open or not,
  required permissions).
- **How the service provider converts text and remembers it**: `Transcriber` Press
  `TranscribeMethod` to send a request. It is only based on the protocol and not based on the model
  name. The error reporting method uses the same set of `ModelHttp` mapping as the conversation
  model. Five forms: uploading files through the audio transcoding interface
  (`TranscribeMethod.AudioTranscriptions`), `input_audio` with bare base64 in the conversation
  interface and adding a transliteration command (`TranscribeMethod.ChatInputAudio`), the same
  `data` with data URI (`TranscribeMethod.ChatInputAudioUri`), Gemini's inline data
  (`TranscribeMethod.GeminiInline`), `input_audio` with data URI in the conversation interface And
  it only sends audio without commands (`TranscribeMethod.ChatInputAudioOnly`). When selecting a
  model, `detect` uses one second of mute and tries in sequence (Gemini only tries its own kind;
  others try from the first to the last). The first one that passes is the method of this model,
  save it into `SpeechChoice.Provider.method`, and use it directly every time after rewriting.
  `TranscribeMethod.ChatInputAudioOnly` is placed last, because the general model will also respond
  to 2xx (recording as chat) for audio-only requests, and the first one will be mistakenly selected.
  Only if "the interface is not used in this way" (can't be found, the format of the response is
  wrong), then change to the next one; if there is a problem with Key or quota, it will be useless
  to try again, just throw it away. Enumerations can only be added later and cannot be renamed or
  deleted. Only the old settings can be read back. Selecting a model on the settings page
  (`ui/SpeechModelScreen.kt`) will trigger the trial transfer. Only select and return after passing
  the test. Only those with names like voice models (`looksLikeSpeech`) are listed in the list. If
  there are no models, you can manually fill in the model name.
- **Dialog Input Box** (`ui/VoiceHold.kt`): The gesture covers the **unfocused** input box. Clicking
  it will still focus on playing the keyboard. Press and hold for about 300 milliseconds before
  starting to listen; when the input box is focused, this gesture is not installed, and long
  pressing is still the system's own word selection and pasting. At this time, there is an extra
  microphone between the input box and the send key. Press and hold it to listen. Clicking it only
  prompts "press and hold to speak". Press and slide up 80dp to "release to cancel". The words
  spoken after releasing the message will only be filled in the input box and not sent. The user
  will change the message before sending it. The service provider will display "Transfer to text..."
  as soon as the message is released. Please change it and then fill it in again. You can wait up to
  30 seconds (`ProviderSpeech.TRANSCRIBE_TIMEOUT_MS`) for the message to be transferred. When it
  times out, you will receive a message indicating that the network is unavailable, and it will not
  keep spinning. After releasing it, you can press and hold it again when it is still spinning (or
  the built-in recognition is still finishing), discard the previous paragraph, and start listening
  again. The same goes for the capsule. If you press it too short (less than 0.5 seconds from when
  you press it with your finger), it will be an accidental touch. Do not turn it to avoid wasting
  your credit. This section will be lost when you leave the page, transfer the screen, or go to the
  background of the app.
- **Suspension Capsule** (`capsule/CapsuleVoice.kt`, `CapsuleController`'s `talkHold` and
  `CapsuleTalk`): Long press to move `cap/phone/a11y/OverlayHost.kt`'s `OverlayHold`, the finger
  does not move for 400 milliseconds after pressing to be considered a long press (`HoldGesture`
  judgment, first moving over the touch threshold is dragging, and then it is no longer counted as a
  long press), `start/move/end/cancel` is called back to the controller. When you press it, the
  entire screen will be darkened, and a panel will appear next to the capsule with two drop points
  "Cancel" and "Change"; release it elsewhere and directly send it to the thing it is facing at the
  moment (a conversation to continue chatting, a run waiting for an answer, etc.), drag it to
  "Change" to open the card and make changes, drag it to "Cancel" and discard it. Screen readers use
  the "speaking" operation: click to start, click again to send. There are no fingers, so they can
  only speak. When the capsule is talking, it changes to "You handle it" (or it can no longer be
  said). This sentence is simply discarded and the microphone is released. The recording occurs in
  the floating window gesture of the accessibility service. When pressed, the finger is on the
  screen and the front desk service is not started.
- **60 seconds cap**: Both the input box and the capsule can be listened to for a maximum of 60
  seconds (`MAX_MS`) at a time. When the point is reached, they will be released and will not be
  held back. The last 10 seconds of the input box will count down in the lower right corner of the
  card. `HoldToTalk.tick` is adjusted to `release` at the right time, because the recording stops at
  the right time without further notification, and the last words are lost without ending.
- **Recording Permission**: Only required on first press and hold (`RECORD_AUDIO`). In the input
  box: when there is no permission, the system box will pop up; when it is rejected and the system
  no longer pops up the box, change the pop-up description, and the button will go directly to
  Mina's permission page in the system. When recording has been allowed and the recognition service
  still reports no permission, or a recording error (`ERROR_AUDIO`, `SpeechFailure.AudioError`) is
  reported, another explanation is displayed: whether the microphone usage master switch in the
  quick settings (from Android 12) is turned off, whether Mina's microphone is turned off separately
  in the system permission management, or it may be occupied by another application; the button
  still opens Mina's application details in the system. The identification service reports an error
  on the client side, throws an error when starting to listen, the service provider's recording
  cannot be started, and still only prompts that the microphone may be occupied by other
  applications. In the capsule: Do not start without permission, pop up a prompt and click back to
  Mina (`capsule_voice_need_mic`) to authorize. `VoiceInput.start` will only send a session of
  `Failed` when there is no permission and the engine cannot be used. The interface will handle the
  same set of failures without making separate judgments.

The `content-transcribe` skill is Agent's transcription of **audio files**, and the voice input here
are two different things, independent of each other.

## 17. Application access

Restrict apps that can be read and operated by standard mobile tools by package name. See the rules
and boundaries.[`app-access.md`](app-access.md)。

- **Policy** (`core/access/AppAccess.kt`): Applications without settings are allowed by default, and
  classification does not determine permissions. An empty block list will not be rejected due to
  missing package names; if it is not empty and the ownership is unknown, it will wait briefly and
  reread it. If it is still unknown, `app_access_unverified` will be returned. File unavailability
  returns `app_access_unavailable`, reset by user in settings.
- **Access Control** (`core/uistate/AppAccessScreenPort.kt`): The mobile phone port of the packaging
  task, protecting the screen reading results and explicit targets; `ActionExecutor` checks before
  and after the action and refuses to return the model with tool results. Each member of
  `ScreenPort` is explicitly written in the access control, without requiring `by` delegation. The
  test checks each member one by one to "transfer to the port inside" and "all are checked except
  for a few that do not touch the App interface." Ports added to members in the future will not be
  missed. The Android port only provides window ownership (`AndroidScreen.accessPackages`), and you
  only see the list where the target cannot be seen in the access control: it does not specify who
  will receive the link and system action of the App and open it to the App on the user's home
  screen; there is no downward transition to read the settings. `ScreenSession` Clear old element
  references, screenshots and positioning cache by permission revision.
- **Overhead**: Nothing more is done when the list is empty (no window, no system prompts, no
  application access section). When the list is not empty, the screen reading directly uses the
  window ownership in the read screen to check, without asking the window; other reading and action
  checking results will be used for up to 1 second when the window has not changed (the accessible
  window change count has not changed) and the list has not changed. The screenshots and task
  preview frames that follow the same screen reading therefore do not need to ask the window every
  time.
- **Prompt word**: The rules for application access are in `prompts/capabilities/app-access.md`,
  which is only connected after the mobile phone section when the list is not empty (or the setting
  cannot be read); it is disabled only when the task is in progress, and the instructions that come
  with the rejection result still tell the model what to do next.
- **Storage and Interface** (`app/settings/AppAccessStore.kt`, `app/ui/AppAccessScreen.kt`): process
  sharing, atomic writing; do not upload the list when opening the settings page, and confirm
  sending the application name and package name before refreshing the category. `AppClassifier`
  reuses the existing model configuration and does not change the permissions if it fails. Pages,
  categories and sharing copy support English, simplified and traditional Chinese.
- **Active sharing** (`app/cap/phone/a11y/ScreenShareConsent.kt`, `ShareChoice.kt`): Users can allow
  only screen sharing, allow this application, or cancel; independent sharing permission does not
  lift automatic operation restrictions, and review sources and permissions before and after
  collection. Sensitive authorization interfaces can only be operated by users.
- **Boundary**: Shell commands, sandbox, and computer access (MCP) are not restricted by this access
  control; calls to standard mobile tools within scripts are still subject to inspection. Models
  that encounter restricted applications should re-evaluate the route and specify the allowed
  locations in "Settings › Application Access" if necessary.

