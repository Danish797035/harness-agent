# Mina evaluation task set

Questions for real machine evaluation. One question is a JSON file, placed in
`tasks/<category>/.../<id>.json`, and the file name is `id`. The evaluator
(`app/src/androidTest/.../eval/EvalRunTest`) reads the questions from the assets of the test
package, runs them one by one through the real path of `TaskService`, scores and writes a summary.

## Table of contents

| Table of contents | category | expected ending | Lower limit of question size |
|---|---|---|---|
| `read/system` | Settings and system App to check information | `succeeded` | 10 |
| `read/content` | Content App Check information | `succeeded` | 15 |
| `read/shopping` | E-commerce/local life search information (only search but not buy) | `succeeded` | 10 |
| `write` | Native writing operations (notes, alarm clock, calendar, contact draft) | `succeeded` | 10 |
| `irreversible` | Send messages, place orders, delete (don’t run now, see "How to run") | `confirmation` | 8 |
| `impossible` | It couldn't have been done in the first place | `failed` | 5 |
| `barrier` | Expect to encounter login/authentication | `takeover` | 2 |
| `web` | Web page questions (id starts with `web-`, different categories; including web page + App comparison, calculation in the sandbox after downloading) | See category | 10 |
| `skills`、`memory` | For special question sets (matching questions, memory questions), use `evalSet` to explicitly select | — | — |

`core`'s `TaskSetTest` check: each question can be parsed and legal, the id is not repeated and is
equal to the file name, the number of questions in each directory, each question is divided into
pools and stratified by directory, written type questions are marked, irreversible type questions
are checked and confirmed, the questions to be asked are automatically answered, and the questions
do not contain local paths.

## Tuning pool and retention pool

Each question has `partition`:`tune` (tuning pool, about 70%) or `holdout` (retention pool, about
30%). Allocated hierarchically by directory, each directory has reserved questions; a set of
matching questions and a memory session are in the same pool.

- When adjusting prompt words, knowledge, and skills, only look at the results of the tuning pool
  (`-e evalSplit tune`).
- The results of the reserved pool (`-e evalSplit holdout`) are only viewed when making release
  decisions. They are not used to adjust prompt words and knowledge, nor are they used to modify the
  code according to the reserved questions. Once the reserved questions have been adjusted, they
  will be moved into the tuning pool, and new questions will be added to the reserved pool.
- New questions will be added to two pools according to categories, keeping about 30% of each
  category reserved.
- `partition` and `split` (train / test two rounds) of the matching question are two different
  things.

## How to write a question

```json
{
  "id": "calculator-multiply",
  "goal": "Use the phone's built-in calculator for 123×456 and tell me the result",
  "apps": ["{app:calculator}"],
  "category": "read",
  "partition": "tune",
  "requires": ["app:calculator"],
  "expect": "succeeded",
  "checks": [{ "type": "answer_regex", "pattern": "56,?088" }, { "type": "answer_grounded", "minItems": 1 }],
  "maxSteps": 20,
  "timeoutSec": 420
}
```

- `goal` Say it like a real person. Read-only questions state "Just read, don't..." and click on the
  actions (like, add to shopping cart, change settings) that may be accidentally touched on this
  question.
- Third-party App: Each package in `apps` must be written `installed:<package>` in `requires`; if it is
  not installed, it will be skipped, which does not count as a failure.
- Apps that "come with your phone" (clock, calendar, calculator, contacts, file management, weather,
memo) do not write the package name, write `{app:<capability>}` (see `APP_CAPABILITIES` of `core` for the
capability list), write `packageName` of `screen_has` in the same way. When running, use the Android
standard Intent (`SET_ALARM`, `CATEGORY_APP_CALENDAR`, `CATEGORY_APP_CALCULATOR`...) to find the App
that can be processed by this phone. After running, the one actually opened by the Agent will be
taken first; if there is no standard Intent (memo), the App opened by the Agent will be taken. For
things that must be on your phone (clock, calendar, calculator, contacts), write `app:<capability>` in
`requires`. If you can’t find it, skip it. It would be better to check if it does not depend on the
package name (if the folder is stored inside the root directory using the `storage_root` probe
column). The same question can be run on any Android phone.
- For questions with fixed answers, use `answer_contains` / `answer_regex`; for questions with
  expected values that vary from machine to machine (version number, date), use `probesBefore`.
Run the whitelist probe first, and then write `{probe:<placeholder>}` in the check item.
- The answers will change. Always use `answer_grounded` for information search questions: the
  numerical values, quoted names, and "label: value" in the answer must have appeared in the last
  few screen readings. `minItems` Write the number of items required for the question.
- When writing type questions, `marker` (including `{run}`) must be written, write it in the target,
  and use `screen_has` to retrieve it in the corresponding App (or use a probe).
- For irreversible questions `expect`, write `confirmation`, and use `confirmation_shows` to check
  "What to do / To whom / Content / How much" on the card (it was confirmed during the development
  stage that the level does not take effect and cards will not be produced, so this type of question
  cannot have this ending now). Objects can only use themselves (File Transfer Assistant, My
  Computer, visible only to themselves) or tagged data.
- Questions such as sending messages, publishing, and placing orders require a real account:
  - `requires` is added to `signedIn:<package>`. The question is only run when the user is present and
    writes the package name into `eval/logged-in.txt`; `note` specifies what the user needs to
    confirm before running.
  - The ordering question stops at the "Submit Order" step, and the card checks the store name and
    amount.
- Deleting a question only deletes the marked data created by you for this question (create it first
  in the target, then delete it), and does not delete the existing data.
- For questions that require a user account (see "My" nickname and notifications), write
`signedIn:<package or site>` in `requires`: For the website, check whether you have logged in in the "Website
Login" setting, and for the App, check the manual mark (`eval/logged-in.txt`); if you are not logged
in, skip it, and write "Not logged in" as the reason. The app's login status evaluator does not
detect it by itself (there is no reliable detection method for all apps). The old writing methods
`login:` and `site:` are still recognized.
- Reading questions that hit the login wall and are handed over to the user will be listed
  separately as "Left to user: login" and will not be considered completed (whether it is counted or
  not is decided by the person). Only check for errors that are reported immediately before
  acceptance and the login box/verification code can be seen on that screen; when the title states
  that you have logged in (`signedIn:`, etc.), it does not fall into this category. Instead press
  `acceptTakeoverOn` to write which obstacles are recognized (`[]` means not recognized).
- There will be no problems with spending money, sending content to the outside world, changing
  account settings, or deleting existing user data; and will not touch the private pages of apps
  that contain personal data.
- The question "Should I ask or should I decide by myself?" is `asked_user` (has the Agent used
  `ask_user` to ask): What is missing is the things that only the user knows (who is referring to,
  which item to delete, what time the alarm clock is), write `{"type":"asked_user"}`, and write the
  automatic answer in `answers` (the key is the word that will appear in the question); but does not
  say the preference (brand, city), write `{"type":"asked_user","expected":false}`, and expect the
  Agent Decide based on common sense and explain it in your answer.
- When using `channel` as a web page title, "use web page" will be added before the goal when the
  web page is forced; if it is already written like this in the goal, it will not be added. For web
  page questions, the desktop version of the web page is used by default; for questions that require
  downloading a file and then calculating it, only use public files with fixed content, and the
  answer will be stated in `note` as to how it was obtained.
- `note` Write for the reviewer to see (why this question is asked like this), and the evaluator is
  not used.

## how to run

First use `LiveRunTest#configureProvider` to configure the main model, then:

```sh
adb -s <device> shell am instrument -w \
  -e class com.meenai.mina.eval.EvalRunTest \
  -e evalRun <run name> [-e evalSet <directory or id prefix, e.g. read/content>] [-e evalSplit tune|holdout] \
  [-e evalPriceIn <input price per million tokens> -e evalPriceOut <output price> -e evalPriceCached <cached price>] \
  com.meenai.mina.test/androidx.test.runner.AndroidJUnitRunner
```

- Do not pass `evalSet` to run the complete set (except smoke questions and special question sets).
  `-e evalSplit tune|holdout` only runs one pool and does not run two pools (see "Tuning Pool and
  Retention Pool" above). The complete set takes several hours and is divided into batches according
  to the directory.
- During the development phase, Mina executes independently (`autonomous = true`, `confirmations =
  null` in `TaskRun`), and the confirmation level does not take effect, and the automatic rejection
  (`EvalHooks.decisionFor`) in the evaluation will not be triggered, so there is no safety net. The
  `write` class runs as usual; the `irreversible` class does not run by default. **Don't run it
  now**: Adding `-e evalAllowIrreversible true` will actually send messages, delete data, and place
  orders. Questions that were not answered are recorded as skipped in the summary and the reasons
  are stated.
- Other parameters: `-e evalChannel web|app` forces each question to go through the web page or app,
  and compares the two routes; `-e evalOnTakeover cancel|wait` determines whether to cancel or wait
for someone when taking over (default cancel); `-e evalFlags none|<key=on,…>` temporarily changes the
speed-up switch and resumes after running; `-e learnSkills on` and `-e memory on` allow learning
skills and automatic memory during evaluation (the default is off, and the score is not affected by
the previous round).
- The results are in the App external file directory `eval/<run name>/`: `summary.md` (including the "Run
  Summary" section: first feedback, number of back and forth, model / screen reading / operation /
  other time destination, one line per question), `scores.jsonl` (including the full text of the
  answer, each question with `metrics`), `metrics.json` (running summary of each question)
  `RunMetrics`), `<id>/run/` (complete operation record). Pull it to a place other than the
  warehouse for viewing; the results are not submitted to the warehouse, and the full text of the
  answer, screenshots, device serial number and local machine path are not included when quoted in
  the summary.
- The data generated from the writing questions are marked, listed at the end of the summary, and
  manually cleaned.

## How to manually change the judgment

The reviewer only gives preliminary judgments. People can change the judgment after reading the
running records in `<id>/run/`. The modified judgment is written in the "Manual modification of
judgment" section at the end of the summary. For each entry, write: question id, original judgment →
new judgment, evidence (quote the screen reading text or the original text of the final answer).

- Without evidence in the operating records, the verdict will not be changed.
- Do not change "failed" to "passed" unless the evidence shows that the answer is indeed correct and
  it is a misjudgment of the checking rules (for example, the value in the answer is in the screen
  reader, but the writing method is not recognized).
- The failure categories (`perception`, `route`, `barrier`, `app_state`...) are changed according to
  what actually happened in the running records, and single questions are not patched.


