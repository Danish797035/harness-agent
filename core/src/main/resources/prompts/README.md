# prompts/: Bundled Prompts

All fixed text sent to models is stored here rather than as string literals in code, read uniformly via `core.prompt.Prompts` / `core.prompt.Feedback`.
Variables in the text formatted as `{{name}}` are filled by the code loading them (limits, options, on-screen names, etc.); missing variables raise errors directly.

## Layers

| Directory | What it is | Where it goes | Read by |
|---|---|---|---|
| `system/identity.md` | P0 Identity & stance: act on user's behalf, within user authorization, look before acting, no pretense, admit unknowns | Start of system prompt | `SystemPrompt.identity()` |
| `system/principles.md` | P1 General principles: whose words count, how to execute tasks, evidence-based replies, obstacles, finding things, safety; holds across all surfaces and capabilities | Directly after identity | `SystemPrompt.identity()` |
| `capabilities/` | P2 Capability descriptions, one per capability; `general.md` defines common tools (report failure, ask question, confirm) and overall completion rules; `groups.md` explains entry points for tool groups; `handover.md` adds user handover and pre-submission checks when a surface group expands; phone, web, and sandbox sections are appended only when that group expands; `app-access.md` specifies app access rules when apps are restricted | System prompt | `SystemPrompt.assemble()` |
| `tools/` | P3 Description for each tool: usage, parameters, result handling; when multiple tools share a template, `promptFile` is declared in the tool | Tool definitions | Tools themselves |
| `entries/` | Short descriptions for entry tools when their group is collapsed; replaced by full text from `tools/` when expanded, with parameters unchanged | Tool definitions | `ToolRegistry` |
| `feedback/` | Feedback from tool results and the main loop: hints, error descriptions (`failure.<errorCode>`), reminders, reference source tags; one key per item | Tool results, context messages | Tools, `AgentLoop` |
| `roles/` | Prompts for internal roles: verification, screen description, location, memory extraction, route matching, skill drafting, app knowledge, post-turn outcome judging | Respective role requests | Respective roles |

App knowledge, site knowledge, and skills are not here; see `knowledge/` and `skills/`.

## Placement Rules

- The higher the layer, the more general and neutral: `system/` mentions no specific tools, apps, or technologies; capability-specific rules belong in that capability's description.
- Information tied to a specific tool (how to use it, parameter meanings, result handling) belongs in `tools/<tool>.md`, never in `system/`.
- Rules that only hold when a capability is present belong in `capabilities/<capability>.md`, sharing the same toggle with that tool group. When a line requires tools from another capability, prefix it with `<!-- needs: tool_name -->`: the line is kept only when that tool is available.
- `system/` mentions no tool names (enforced by tests; English action words like tap or back do not count).
- Tool result descriptions go into `feedback/tools.md`; main loop notifications go into `feedback/loop.md`; keys are unique across both files and every key must be referenced by code.
- Each feedback entry is self-contained: no leading/trailing whitespace, code never concatenates it with strings; compositions are defined within templates using `{{placeholder}}`.
- System prompts within a task only change when a tool group expands (cache invalidates at most once per group); turn-variable data (step counts, timestamps, foreground app) is never placed in fixed prompt text.

## Format

- `tools/`, `system/`, `capabilities/`, `roles/`: each file is a single verbatim passage, trimmed of leading and trailing whitespace on read.
- `feedback/*.md`: may begin with an optional `<!-- comment -->`; each entry begins with a standalone `## key` line (lowercase letters, digits, `_`, `.`), followed by the content up to the next entry.

## Size

UTF-8 byte counts for each layer are tracked in the gold master `core/src/test/resources/prompt-surface/sizes.txt` for reference without hard limits: growth from every change appears in commit diffs. Before adding content, check whether old text can be pruned; explain significant increases in commit messages.

## After Making Changes

All text visible to models has gold master snapshots: `core/src/test/resources/prompt-surface/` (system prompts, tool definitions, feedback, roles) and `loop-snapshots/` (mock model replay message sequences). Intentional changes are regenerated with `./gradlew :core:test -PupdateSnapshots` and reviewed in the commit.

## Tool Names

- Phone and web each have a set of screen tools (`phone_*`, `web_*`, with matching verbs and parameters); identical verbs share `tools/<verb>.md` templates where `{{verb}}` is substituted with the tool name; surface-specific documentation uses `tools/<surface>_<verb>.md`.
- `capabilities/general.md` mentions no capability-specific tools (enforced by tests); channel selection is described in `channels.md`, appended only when more than one surface is available.
- All tool names mentioned in knowledge, skills, and prompts must be currently registered (enforced by tests).
