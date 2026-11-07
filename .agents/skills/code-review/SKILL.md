---
name: code-review
description: Subagent-coordinated hybrid code review. A Pro model acts as the moderator (orchestrator) and runs, in parallel, a fast meta scan by Flash subagents and a phase-by-phase deep logical review by Pro subagents, then generates an integrated report.
skills:
  - file-enumerator
  - code-review-rules
  - gpu-code-review-rules
  - phased-session-manager
---

# Subagent-Coordinated Hybrid Code Review Process

> Tool names use the Antigravity notation. In other environments, read them through the table in `.agents/rules/agent-tool-mapping.md`.

## Model Precondition Guard (Early Exit)

1. **Identify the model**:
   Refer to the system context (`<USER_SETTINGS_CHANGE>` or the runtime `Model Selection` metadata) and confirm which main model is currently running the inference.
2. **Abort immediately when a Flash model is detected**:
   - If the `Model Selection` value contains **`Flash`** (e.g. `Gemini 3.8 Flash`), **stop immediately without processing the task or running any tool**.
   - Output only the following guidance message (in Japanese, the user's language) and end the response:
     > ⚠️ **Please switch the model**: The main agent of this workflow (moderation, overall design, final assessment) requires high-difficulty reasoning, so it is **for the 🤔 Pro model (or a higher model) only**. Switch to the 🤔 **Pro model** in the model selector of the chat screen and run it again. (Note: the Flash model for the fast scan is started automatically inside as a subagent.)
3. **For a Pro / higher model**:
   - Start the normal processing below.

---

## Global Role and Absolute Constraints (most important)

From the moment this prompt is loaded, you, the main agent, act as a **world-class software architect and quality assurance specialist** and as a **strict orchestrator (moderator)** that directs the group of subagents.

Obey the following absolute constraints in every situation:

1. **The main agent must not read the code directly (context protection)**:
   The main agent itself must not load and review all of the source code under review. The meta scan of the file set (Flash) and the deep review of each phase (Pro) must always be delegated to subagents with the **`invoke_subagent` tool**.
2. **Run non-stop to the end (no pauses between phases, no monitoring script)**:
   The main agent's context is always kept clean, so waiting for user approval for each phase (a "yes/y" confirmation) and starting a background monitoring script (`session.js monitor`) are **not needed at all**. Proceed autonomously and non-stop from STEP 0 to STEP 4 (the final assessment output).
3. **Never generate the plan or review automatically with a script**:
   Writing and running scripts in PowerShell, Python, etc. to automate generating plan files or review result JSON is strictly forbidden (always include this constraint in the instructions to subagents as well).

---

## STEP 0: Session initialization and preparation (run by the main agent)

1. **Initialize the session**:
   Run the following command to obtain the common session ID `{SSS}`.

   ```bash
   node .agents/skills/phased-session-manager/scripts/session.js init --scenario code-reviews --sub-id main
   ```

2. **Extract the target files (generate `targets.json`)**:
   If the result of the `init` above is `"status": "new"` (or `./tmp/code-reviews/{SSS}-targets.json` has not been generated), run the following command to extract the files to scan. If the user specified target paths (one or more) in the prompt, pass them as they are as arguments.

   ```bash
   node .agents/skills/file-enumerator/scripts/list-files.js [user-specified paths (one or more)] -o ./tmp/code-reviews/{SSS}-targets.json
   ```

   **File list mode**: if the user passes a "newline-separated file list", pass it as it is, without processing or interpreting it, as the argument of `--list` (`-l`) (the script handles blank lines, `#` comments, duplicates and excluded files properly).

   ```bash
   node .agents/skills/file-enumerator/scripts/list-files.js --list "<the list the user passed (newline-separated)>" -o ./tmp/code-reviews/{SSS}-targets.json
   ```

   After the extraction, register the target files in the session context:

   ```bash
   node .agents/skills/phased-session-manager/scripts/session.js set-targets --scenario code-reviews --sub-id main --session-id {SSS} --targets-file ./tmp/code-reviews/{SSS}-targets.json
   ```

3. **Measure test coverage beforehand**:
   To raise the review accuracy, run the following command to generate `./tmp/code-reviews/{SSS}-coverage.json`.

   ```bash
   node .agents/skills/code-review/scripts/collect-coverage.js --session-id {SSS}
   ```

---

## STEP 1: Parallel meta scan by Flash subagents (Fast Indexing Phase)

The main agent checks `./tmp/code-reviews/{SSS}-targets.json` and splits the target files into **chunks of about 10 to 15 files each** (exclude generated code such as `*.g.cs` and `*.generated.*`).

### 1-1. Start the Flash subagents as a parallel batch

Use the `invoke_subagent` tool to start, as a parallel batch, one subagent per chunk with **`Model: "flash"`**.

- **`TypeName`**: `"self"`
- **`Model`**: `"flash"`
- **`Role`**: `"Flash Meta Scanner (Chunk 01)"` (append the chunk number)
- **`Prompt`**: give the following instructions clearly.
  1. Always actually read the assigned target file list with the `view_file` tool or similar, and extract mechanically the places that match the following **six spot criteria** (do not include clean code):
     - `code_duplication`: places whose processing structure or logic closely resembles other files or methods (state the target in `notes`)
     - `complex_layout`: complex visual structure such as deep nesting or extremely long blocks
     - `conditional_bloat`: multiple branching through consecutive `switch-case` or `if-else-if`
     - `silent_error`: empty `catch` blocks, or places that only write a log without rethrowing or an alternative handling
     - `io_interactions`: direct calls to DB operations, file I/O, HTTP communication, concurrency (Thread/lock)
     - `cross_cutting`: exception wrapping, logging, locking, etc. mixed directly into the core logic
  2. Save the extraction result to `./tmp/code-reviews/{SSS}-meta-index-chunk-{2-digit chunk number}.json` (e.g. `001-meta-index-chunk-01.json`) in the following JSON format:
     ```json
     {
       "meta_index": [
         {
           "file_path": "relative file path",
           "type": "class | method",
           "name": "class name or method name",
           "line_range": "start line-end line",
           "detected_flags": ["complex_layout", "io_interactions"],
           "notes": "a brief note on the concrete visual feature or the duplication target"
         }
       ]
     }
     ```
  3. When the file is saved, do not include the full JSON in the chat output; report only the saved file path and the number of detections, briefly, and finish.

### 1-2. Merge and normalize the meta index

When completion notices have been received from all Flash subagents, the main agent runs the following command to combine all chunks, remove duplicates, normalize paths, and finalize `./tmp/code-reviews/{SSS}-meta-index-active.json`.

```bash
node .agents/skills/code-review/scripts/normalize-meta-index.js --session-id {SSS}
```

---

## STEP 2: Phase split and parallel precise review by Pro subagents

### 2-1. Build the phases from a bottom-up layer classification

The main agent refers to `./tmp/code-reviews/{SSS}-targets.json` and `./tmp/code-reviews/{SSS}-meta-index-active.json` and classifies the target files starting from the downstream layers with the deepest dependencies.
To maximize the context density of each subagent, split them into **about 3 to 5 files per phase**, and add a branch number (`01-01`, `01-02`, etc.) where necessary.

1. **Phase 01**: Domain and model layer
2. **Phase 02**: Core algorithm and signal processing layer
3. **Phase 03**: Infrastructure, I/O and parser layer
4. **Phase 04**: Use case and core logic service layer
5. **Phase 05**: Presentation, controller and state management layer
6. **Phase 06**: View, UI rendering and behavior layer
7. **Phase 07**: Unit test layer
8. **Phase 08**: Integration test layer (always place the tests in the final phase groups)

### 2-2. Start the Pro subagents as a parallel batch

Based on the phase list you created, use the `invoke_subagent` tool to start, as a parallel batch, one subagent per phase with **`Model: "pro"`** (if there are very many phases, you may start them in batches of 3 to 5 subagents).

- **`TypeName`**: `"self"`
- **`Model`**: `"pro"`
- **`Role`**: `"Pro Code Reviewer (Phase 0X-0Y)"`
- **`Prompt`**: pass each subagent the following instructions, the list of target files, the session ID `{SSS}` and the phase identifier (e.g. `phase01-01`).

#### [Mandatory instructions for Pro subagents]

1. **Absolute constraints**:
   - It is strictly forbidden to write and run scripts for the purpose of generating the plan or the review JSON.
   - Read the assigned target files one by one in order, and read them carefully and autonomously, including the wide context of callers and callees.
   - Select the "Code Smells" you point out strictly, only those that are necessary and sufficient and that seriously affect architecture or maintainability.
2. **Refer to skills and specifications**:
   - Read the skill for the area (`.agents/skills/code-review-rules/SKILL.md` or `.agents/skills/gpu-code-review/SKILL.md`) and follow its evaluation criteria.
   - If the target is G# code, read the `.md` files in `P:\Projects\Active\Henicos\docs\spec\en` and verify violations of constraints such as memory limits.
   - Refer to `./tmp/code-reviews/{SSS}-coverage.json` and `./tmp/code-reviews/{SSS}-meta-index-active.json` (the part for the relevant files).
3. **Deliverable 1: output the per-phase plan file**:
   - Output a plan describing the target files, the purpose of the review and the commit-splitting strategy to `./tmp/code-reviews/{SSS}-phase{XX}-plan_{YYYYMMDD_HHMMSS}.md`.
4. **Deliverable 2: output the review result in a strict JSON schema**:
   - Output to `./tmp/code-reviews/{SSS}-phase{XX}-review_{YYYYMMDD_HHMMSS}.json`.
   - In `refactoring_proposals`, derive the best refactoring proposal based on Martin Fowler's classification of "Code Smells" (the minimum necessary) and on an analysis of "commonality (invariant behavior)" and "variability (factors that will change in the future)" (CVA) that takes the latest paradigms of the target language into account.
   - **Conform 100%** to the following JSON schema, and never add keys of your own:

```json
{
  "phase_title": "【Phase 01-01】Component name",
  "target_files": ["src/Models/Foo.cs"],
  "architectural_summary": "Summary of the biggest bottleneck and the ROI of the refactoring.",
  "verification_summary": "Summary of the results of the tests, build, static analysis, etc. that were run.",
  "defects": {
    "robustness": { "fatal": ["file path: line number - defect description"], "major": [] },
    "responsibility": { "fatal": [], "major": [], "minor": [] },
    "cognitive": { "major": [], "minor": [] },
    "risk": { "fatal": [], "major": [] },
    "roi": { "major": [] },
    "bonus": { "patterns": false, "edge_cases": false, "details": [] }
  },
  "refactoring_proposals": [
    {
      "priority": "fatal | major | minor",
      "what_why": "[Name of the smell (keep to the minimum necessary)] and [result of the commonality and variability analysis]. Describe the phenomenon and the root cause logically.",
      "source_of_risk": "The concrete place in the code or the data flow that causes the risk.",
      "how": "A concrete design change to separate and encapsulate the identified 'variability' (including an alternative using the latest paradigms of the target language, with the trade-offs considered)."
    }
  ],
  "test_strategy": {
    "coverage_measured": "XX%",
    "additions": "Points out under-covered places and high-ROI test paths.",
    "reductions": "Points out excessive mocks, or advises keeping things as they are."
  }
}
```

5. **Completion report**:
   - Do not output the full JSON to the chat; reply to the main agent briefly with only the file paths of the generated plan file and review JSON and whether any critical defect was detected, and finish.

---

## STEP 3: Merge into the integrated report and extract the findings (run by the main agent)

When completion notices have been received from all Pro subagents, the main agent runs the following steps synchronously.

1. **Record the completion of the session state**:
   ```bash
   node .agents/skills/phased-session-manager/scripts/session.js update --scenario code-reviews --sub-id main --session-id {SSS} --next-phase DONE
   ```

2. **Merge into the integrated report and extract the findings**:
   ```bash
   node .agents/skills/code-review/scripts/merge-code-reviews.js --session-id {SSS}
   node .agents/skills/code-review/scripts/extract-issues.js --session-id {SSS}
   ```

---

## STEP 4: Assessment and proposals based on the summary (run by the main agent)

Read **only** `./tmp/code-reviews/{SSS}-extracted_issues.md` (do not read the integrated report itself, to save context) and output the following to the user.

1. **Overall assessment**: an overall assessment of the architecture and code quality (including the total score and the quality gate result)
2. **What to improve right away**: a summary of the red cards (Fatal / Major) that need urgent fixes
3. **The most recommended next action**: the highest-ROI improvement proposal given the current state

---

## STEP 5: Build the refactoring plan

1. Refer to `./tmp/code-reviews/{SSS}-extracted_issues.md` and draw up a plan that gathers the concrete implementation policy, the task split and the impact range.
2. Do not output the approach and design you drafted in full in the chat; **create them as a dedicated artifact (`implementation_plan.md`)**.
   - **Location**: under the agent's artifact directory (`implementation_plan.md`)
   - **Metadata**: specify `ArtifactMetadata` when running `write_to_file`, and to enable the user's approval (the Proceed button), **always set `RequestFeedback: true` and `UserFacing: true`**.
3. In the chat, report an outline of the plan you created and that it is complete, and prompt the user to review and approve the artifact (the Proceed button).
