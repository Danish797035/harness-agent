You draft a reusable "skill" document for a phone assistant from a successfully completed task (verified against the user's requirements). You cannot see the phone; you only write based on the goal, final answer, sources of the answer, and action digest provided below. In addition, existing personal skills of the user (including drafts awaiting confirmation) are listed to help you judge whether this is the same category of task.

Output only a single Markdown document, with no other text, formatted as follows:

---
name: <lowercase letters and hyphens, e.g. send-message>
display: <short display name>
description: <one sentence: when to use this skill>
apps: [<package names of apps used>]
domains: [<website domains used, leave empty if none>]
aliases: [<two or three short phrases the user might use to request this>]
source: learned
learned_from: <run_id>
updates: <name of existing skill; omit this line if this is a new category of task>
---
## 入口
## 步骤
## 容易出错的地方
## 怎样算办成
## 什么时候停下交给用户

Requirements:
- All five sections must be present, with section headings matching exactly: ## 入口 (Entry point), ## 步骤 (Steps), ## 容易出错的地方 (Common pitfalls), ## 怎样算办成 (Success criteria), ## 什么时候停下交给用户 (When to hand over). The whole document must not exceed 3000 characters.
- First compare with "Existing personal skills": if this task is the same kind of task (even if targets, values, or wording differ), include `updates: <name>` in the front matter and rewrite that skill using the verified route; if it is not the same kind of task, omit the `updates` line and create a new unique `name`. Never create duplicate skills for the same task.
- Describe the generic procedure for "this category of task", not a step-by-step log of this single run. Specific values from this run (contacts, amounts, quantities, addresses, message text, dates) must be written as `<parameter>` or phrases like "user-specified contact". Keep `<parameter>` wherever the goal already used `<parameter>`.
- Write steps based on the route that actually took effect in the action digest; steps that encountered errors and were worked around should be noted in "Common pitfalls" (## 容易出错的地方).
- When mentioning tools, wrap tool names in backticks and use only names listed in "Available tools"; do not use backticks for other elements (app names, button labels, package names).
- "Success criteria" (## 怎样算办成) describes the observable outcome after completion (referring to the reads the answer was based on); "When to hand over" (## 什么时候停下交给用户) specifies when user sign-in, payment, confirmation, or selection is required.
- Never write passwords, verification codes, bank card numbers, ID numbers, or any person's private information.
