#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const { parseArgs } = require("node:util");
const {
  checkHelp,
  ensureDirSync,
  getTimestampString,
} = require("./common");

const HELP_TEXT = `
build-prompt.js - 動的プロンプト＆コンテキスト結合ツール

【概要】
  コアレビュー基準、言語別仕様プロンプト、プロジェクト固有プロンプトを結合し、
  動的プロンプトファイル（{SSS}-combined-system.prompt.md）および
  アクティブセッションファイル（{SSS}-active-context.json）を生成します。

【使用法】
  node .agents/bin/build-prompt.js [オプション]

【オプション】
  -s, --session-id <ID>   セッションID（例: 001）。デフォルト: 001
  -l, --lang <LANG>       言語種別（csharp, ts, python等）。デフォルト: csharp
  -p, --phase <PHASE>     対象フェーズ（例: Phase 01）。デフォルト: Unknown
  -f, --files <FILES>     対象ファイル一覧（カンマ区切り）。デフォルト: 空
  -h, --help, /?, /help   このヘルプメッセージを表示
`;

function parseArguments() {
  checkHelp(HELP_TEXT);

  const options = {
    "session-id": { type: "string", short: "s", default: "001" },
    lang: { type: "string", short: "l", default: "csharp" },
    phase: { type: "string", short: "p", default: "Unknown" },
    files: { type: "string", short: "f", default: "" },
  };

  const { values } = parseArgs({ options, strict: false });
  return {
    sessionId: values["session-id"].padStart(3, "0"),
    lang: values.lang,
    phase: values.phase,
    filesStr: values.files,
  };
}

function buildPromptContent(packageDir, projectRoot, lang) {
  const outputMarkdown = [];

  // 1. Core prompt
  const corePath = path.join(packageDir, "skills", "code-review", "SKILL.md");
  if (fs.existsSync(corePath)) {
    const rawContent = fs.readFileSync(corePath, "utf8");
    const cleanContent = rawContent.replace(/^---[\s\S]*?---\r?\n?/, "");
    outputMarkdown.push(cleanContent);
  } else {
    console.error(`Error: Core prompt not found at ${corePath}`);
    process.exit(1);
  }

  // 2. Language spec prompt
  const langPath = path.join(packageDir, "specs", `lang-${lang}.prompt.md`);
  if (fs.existsSync(langPath)) {
    outputMarkdown.push(fs.readFileSync(langPath, "utf8"));
  }

  // 3. Project specific spec prompt
  const projectSpecsDir = path.join(projectRoot, "specs");
  const projPattern = /proj-.*\.prompt\.md$/;
  let matchedProjSpec = null;
  if (fs.existsSync(projectSpecsDir)) {
    const files = fs.readdirSync(projectSpecsDir);
    for (const file of files) {
      if (projPattern.test(file)) {
        const projPath = path.join(projectSpecsDir, file);
        outputMarkdown.push(fs.readFileSync(projPath, "utf8"));
        matchedProjSpec = path.relative(projectRoot, projPath);
      }
    }
  }

  return {
    promptContent: outputMarkdown.join("\n\n"),
    corePath,
    langPath,
    matchedProjSpec,
  };
}

function createContextData(
  projectRoot,
  sessionId,
  filesStr,
  phase,
  corePath,
  langPath,
  matchedProjSpec,
) {
  const targetFiles = filesStr ? filesStr.split(",").map((f) => f.trim()) : [];
  const timestamp = getTimestampString();

  return {
    session_id: sessionId,
    timestamp,
    target_repository: path.basename(projectRoot),
    current_phase: phase,
    completed_phases: [],
    next_target_files: targetFiles,
    applied_specs: {
      core: path.relative(projectRoot, corePath),
      language: fs.existsSync(langPath)
        ? path.relative(projectRoot, langPath)
        : null,
      project: matchedProjSpec,
    },
    status: "In-Progress",
  };
}

function main() {
  const { sessionId, lang, phase, filesStr } = parseArguments();

  const projectRoot = process.cwd();
  const packageDir = path.join(__dirname, "..");
  const tmpDir = path.resolve(projectRoot, "tmp/code-reviews");
  ensureDirSync(tmpDir);

  const { promptContent, corePath, langPath, matchedProjSpec } =
    buildPromptContent(packageDir, projectRoot, lang);

  const promptFile = path.join(
    tmpDir,
    `${sessionId}-combined-system.prompt.md`,
  );
  fs.writeFileSync(promptFile, promptContent, "utf8");

  const contextData = createContextData(
    projectRoot,
    sessionId,
    filesStr,
    phase,
    corePath,
    langPath,
    matchedProjSpec,
  );
  const contextFile = path.join(tmpDir, `${sessionId}-active-context.json`);
  fs.writeFileSync(contextFile, JSON.stringify(contextData, null, 2), "utf8");

  console.log(`Successfully generated dynamic contexts:`);
  console.log(`- State JSON: ${contextFile}`);
  console.log(`- Combined Prompt: ${promptFile}`);
}

if (require.main === module) {
  main();
}

module.exports = {
  buildPromptContent,
  createContextData,
};
