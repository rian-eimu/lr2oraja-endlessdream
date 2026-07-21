#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const { parseArgs } = require("node:util");

function parseArguments() {
  const options = {
    "session-id": { type: "string", short: "s", default: "001" },
    lang: { type: "string", short: "l", default: "csharp" },
    phase: { type: "string", short: "p", default: "Unknown" },
    files: { type: "string", short: "f", default: "" },
  };

  const { values } = parseArgs({ options, strict: false });
  return {
    sessionId: values["session-id"],
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
  const now = new Date();
  const timestamp =
    now.getFullYear() +
    String(now.getMonth() + 1).padStart(2, "0") +
    String(now.getDate()).padStart(2, "0") +
    "_" +
    String(now.getHours()).padStart(2, "0") +
    String(now.getMinutes()).padStart(2, "0") +
    String(now.getSeconds()).padStart(2, "0");

  return {
    sessionId,
    timestamp,
    targetRepository: path.basename(projectRoot),
    targetFiles,
    appliedSpecs: {
      core: path.relative(projectRoot, corePath),
      language: fs.existsSync(langPath)
        ? path.relative(projectRoot, langPath)
        : null,
      project: matchedProjSpec,
    },
    currentPhase: phase,
    status: "In-Progress",
  };
}

function main() {
  const { sessionId, lang, phase, filesStr } = parseArguments();

  const projectRoot = process.cwd();
  const packageDir = path.join(__dirname, "..");
  const tmpDir = path.resolve(projectRoot, "tmp/code-reviews");

  if (!fs.existsSync(tmpDir)) {
    fs.mkdirSync(tmpDir, { recursive: true });
  }

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

main();
