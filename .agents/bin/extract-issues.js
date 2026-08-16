#!/usr/bin/env node
"use strict";

const fs = require("fs");
const path = require("path");
const { parseArgs } = require("node:util");
const {
  checkHelp,
  ensureDirSync,
  resolveSessionId,
} = require("./common");

// ============================================================================
// Constants & Configuration
// ============================================================================

const DEFAULT_REVIEW_DIR = "tmp/code-reviews";
const DEFAULT_FALLBACK_TEXT = "具体的な改善提案はありません。";
const NO_ISSUES_TEXT = "指摘事項（問題ありと判定されたフェーズ）はありませんでした。";

const SUGGESTION_KEYWORDS = [
  "脆弱性",
  "リファクタリング",
  "改善提案",
  "改善案",
  "問題点",
  "指摘事項",
  "検出された問題",
  "課題",
  "修正案",
  "設計図",
  "suggestion",
  "improvement",
  "refactor",
  "vulnerabilit",
  "issue",
  "problem",
  "recommendation",
];

const FACT_KEYWORDS = [
  "評価プロセス",
  "ファクト",
  "検査プロセス",
  "検証サマリー",
  "fact",
  "evaluation process",
  "検出された欠陥",
  "欠陥",
  "defect",
];

const EXCLUDE_FROM_SUGGESTIONS = ["テスト戦略", "test strategy"];

const HELP_TEXT = `
extract-issues.js - 指摘事項＆改善提案 抽出ツール

【概要】
  統合レビューレポート（{SSS}-integrated-review-report_*.md）から、
  合格基準未達（REJECT/FAIL）または減点・欠陥が存在するフェーズの改善提案を抽出し、
  LLMコンテキスト節約用のサマリー（{SSS}-extracted_issues.md）を出力します。

【使用法】
  node .agents/bin/extract-issues.js [オプション] [入力レポートパス] [出力先パス]

【オプション】
  -s, --session-id <ID>   セッションID（例: 001）。未指定時は最新レポートを自動検出
  --include-facts         検出された欠陥の詳細セクションもサマリーに含める
  -f, --force             強制上書き実行
  -h, --help, /?, /help   このヘルプメッセージを表示
`;

// ============================================================================
// CLI & Path Resolution
// ============================================================================

function parseCliArgs() {
  checkHelp(HELP_TEXT);

  const options = {
    "include-facts": { type: "boolean", default: false },
    force: { type: "boolean", default: false },
    "session-id": { type: "string", short: "s", default: "" },
    help: { type: "boolean", short: "h", default: false },
  };

  const { values, positionals } = parseArgs({
    options,
    allowPositionals: true,
    strict: false,
  });

  return {
    includeFacts: values["include-facts"],
    force: values.force,
    sessionId: values["session-id"],
    help: values.help,
    customReportPath: positionals[0] || "",
    customOutputPath: positionals[1] || "",
  };
}

function findLatestReport(targetDir, sessionId) {
  ensureDirSync(targetDir);

  const files = fs.readdirSync(targetDir);
  const matched = files
    .filter((f) => f.includes("-integrated-review-report_") && f.endsWith(".md"))
    .filter((f) => !sessionId || f.startsWith(`${sessionId}-`))
    .map((f) => ({
      name: f,
      mtime: fs.statSync(path.join(targetDir, f)).mtimeMs,
    }))
    .sort((a, b) => b.mtime - a.mtime);

  if (matched.length === 0) {
    console.error(
      `Error: No integrated review report found${sessionId ? ` for session ${sessionId}` : ""}.`,
    );
    process.exit(1);
  }

  return path.join(targetDir, matched[0].name);
}

function resolveFilePaths(customReportPath, customOutputPath, sessionId) {
  const defaultDir = path.resolve(process.cwd(), DEFAULT_REVIEW_DIR);
  let reportPath = customReportPath;

  if (!reportPath) {
    reportPath = findLatestReport(defaultDir, sessionId);
  } else if (!fs.existsSync(reportPath)) {
    console.error(`Error: Input report file not found: ${reportPath}`);
    process.exit(1);
  }

  let outputPath = customOutputPath;
  if (!outputPath) {
    const dir = path.dirname(reportPath);
    const base = path.basename(reportPath, ".md");
    const prefix = base.replace(/-integrated-review-report.*/, "");
    outputPath = path.join(dir, `${prefix}-extracted_issues.md`);
  }

  return { reportPath, outputPath };
}

// ============================================================================
// Phase & Keyword Helpers
// ============================================================================

function normalizePhaseKey(phaseStr) {
  if (!phaseStr) return "";
  const cleaned = phaseStr.replace(/[*_`【】\[\]]/g, "").trim();
  const m = cleaned.match(/(?:Phase|フェーズ)?\s*(\d+)(?:[\-_.](\d+))?/i);
  if (m) {
    const main = parseInt(m[1], 10);
    const sub = m[2] !== undefined ? parseInt(m[2], 10) : null;
    return sub !== null ? `phase-${main}-${sub}` : `phase-${main}`;
  }
  return cleaned.toLowerCase();
}

function classifySectionHeading(rawName) {
  const norm = rawName.toLowerCase().replace(/[*_`#]/g, "").trim();

  const isSuggestion = SUGGESTION_KEYWORDS.some((kw) => norm.includes(kw));
  const isExcluded = EXCLUDE_FROM_SUGGESTIONS.some((kw) => norm.includes(kw));

  if (isSuggestion && !isExcluded) {
    return "Suggestions";
  }

  const isFact = FACT_KEYWORDS.some((kw) => norm.includes(kw));
  if (isFact) {
    return "Facts";
  }

  return null;
}

function isProblematicPhase(facts, gateResult, scoreText) {
  if (/\bF\b[*_]*:\s*([1-9]\d*)/i.test(facts)) return true;
  if (/\bM\b[*_]*:\s*([1-9]\d*)/i.test(facts)) return true;
  if (/\bm\b[*_]*:\s*([1-9]\d*)/i.test(facts)) return true;

  if (/RED\s*CARD/i.test(facts) || /FAIL/i.test(facts) || /REJECT/i.test(facts)) return true;
  if (/FAIL/i.test(gateResult) || /REJECT/i.test(gateResult) || gateResult.includes("💀") || gateResult.includes("🔴")) return true;

  const scoreMatch = (scoreText || "").match(/(\d+)\s*\/\s*100/);
  if (scoreMatch) {
    const scoreVal = parseInt(scoreMatch[1], 10);
    if (scoreVal < 100) return true;
  }

  return false;
}

// ============================================================================
// Markdown Report Parsers
// ============================================================================

function parseQualitySummaryTable(lines) {
  let inTable = false;
  const tableHeaders = [];
  const extractedRows = [];
  const targetPhases = [];
  const phaseMeta = {};

  for (const line of lines) {
    if (inTable && line.trim() === "") {
      break;
    }

    if (
      !inTable &&
      (/^\|\s*(?:フェーズ|Phase)/i.test(line) || /^\|\s*.*Gate\s*\d+/i.test(line))
    ) {
      inTable = true;
      tableHeaders.push(line);
      continue;
    }

    if (!inTable) continue;

    if (/^\|\s*:?---/.test(line)) {
      tableHeaders.push(line);
      continue;
    }

    if (!line.startsWith("|")) continue;

    const columns = line.split("|").map((c) => c.trim());
    if (columns.length < 5) continue;

    const rawPhaseName = columns[1].replace(/\*\*|\*/g, "").trim();
    const title = columns[2] || "";
    const score = columns[3] || "";
    const gateResult = columns[4] || "";
    const facts = columns[5] || "";

    if (isProblematicPhase(facts, gateResult, score)) {
      extractedRows.push(line);
      targetPhases.push(rawPhaseName);
      const normKey = normalizePhaseKey(rawPhaseName);
      phaseMeta[normKey] = {
        rawPhaseName,
        title,
        score,
        gateResult,
        facts,
      };
    }
  }

  return { tableHeaders, extractedRows, targetPhases, phaseMeta };
}

function parseDetailPhaseBlocks(lines) {
  const detailBlocks = {};
  let currentNormKey = null;
  let currentSection = null;
  let sectionBuffer = [];
  let fullPhaseBuffer = [];

  const flushSection = () => {
    if (!currentNormKey) return;
    const text = sectionBuffer.join("\n").trim();
    if (currentSection && text) {
      if (!detailBlocks[currentNormKey]) detailBlocks[currentNormKey] = {};
      if (!detailBlocks[currentNormKey][currentSection]) {
        detailBlocks[currentNormKey][currentSection] = text;
      } else {
        detailBlocks[currentNormKey][currentSection] += "\n\n" + text;
      }
    }
    sectionBuffer = [];
  };

  const flushPhase = () => {
    flushSection();
    if (currentNormKey && fullPhaseBuffer.length > 0) {
      if (!detailBlocks[currentNormKey]) detailBlocks[currentNormKey] = {};
      detailBlocks[currentNormKey]["AllContent"] = fullPhaseBuffer.join("\n").trim();
    }
    fullPhaseBuffer = [];
  };

  for (const line of lines) {
    const phaseMatch =
      line.match(/^#{1,3}\s*【?(?:Phase|フェーズ)\s*([\d]+(?:[\-_.]\d+)?)】?/i) ||
      line.match(/^#{1,3}\s*【(Phase\s*[\d\-_\.]+)】/i);

    if (phaseMatch) {
      flushPhase();
      currentNormKey = normalizePhaseKey(phaseMatch[1]);
      if (!detailBlocks[currentNormKey]) {
        detailBlocks[currentNormKey] = {};
      }
      currentSection = null;
      continue;
    }

    if (!currentNormKey) continue;

    fullPhaseBuffer.push(line);

    const sectionMatch = line.match(/^#{2,4}\s+(.*)$/);
    if (sectionMatch) {
      flushSection();
      currentSection = classifySectionHeading(sectionMatch[1]);
      continue;
    }

    if (line.trim() === "---") {
      flushPhase();
      currentNormKey = null;
      currentSection = null;
      continue;
    }

    if (currentSection) {
      sectionBuffer.push(line);
    }
  }

  flushPhase();
  return detailBlocks;
}

// ============================================================================
// Markdown Document Builder
// ============================================================================

function buildIssuesMarkdown(
  reportPath,
  tableHeaders,
  extractedRows,
  targetPhases,
  phaseMeta,
  detailBlocks,
  includeFacts = false,
) {
  const lines = [
    `# 抽出された指摘事項サマリー (Extracted Issues)`,
    ``,
    `元レポート: \`${path.basename(reportPath)}\``,
    `抽出日時: ${new Date().toISOString().replace("T", " ").replace(/\..+/, "")}`,
    ``,
    `## 1. 指摘対象フェーズ一覧 (Quality Gate Rejection / Issue Rows)`,
    ``,
  ];

  if (extractedRows.length > 0) {
    lines.push(...tableHeaders);
    lines.push(...extractedRows);
  } else {
    lines.push(`> 🟢 **合格 / 指摘なし**: すべてのフェーズが合格基準（Gate Pass / 100点）を満たしています。`);
  }

  lines.push(``, `---`, ``, `## 2. フェーズ別 改善提案詳細 (Detailed Suggestions)`, ``);

  if (targetPhases.length === 0) {
    lines.push(NO_ISSUES_TEXT, ``);
  } else {
    const details = targetPhases.map((rawPhaseName, idx) => {
      const normKey = normalizePhaseKey(rawPhaseName);
      const meta = phaseMeta[normKey] || {
        rawPhaseName,
        title: "No Title",
        score: "Unknown",
        gateResult: "Unknown",
        facts: "Unknown",
      };

      let phaseBlock = detailBlocks[normKey];
      if (!phaseBlock) {
        const keys = Object.keys(detailBlocks);
        const matchedKey = keys.find(
          (k) => k.includes(normKey) || normKey.includes(k),
        );
        if (matchedKey) phaseBlock = detailBlocks[matchedKey];
        else if (keys[idx]) phaseBlock = detailBlocks[keys[idx]];
      }

      const sugContent =
        (phaseBlock && phaseBlock["Suggestions"]) ||
        (phaseBlock && phaseBlock["AllContent"]) ||
        DEFAULT_FALLBACK_TEXT;

      let factSection = "";
      if (includeFacts && phaseBlock && phaseBlock["Facts"]) {
        factSection = `\n\n#### 📝 検出欠陥\n\n${phaseBlock["Facts"]}\n`;
      }

      return [
        `### 🔴 [${rawPhaseName}] ${meta.title}`,
        ``,
        `- **スコア**: ${meta.score}`,
        `- **判定**: ${meta.gateResult}`,
        `- **件数サマリー**: ${meta.facts}`,
        ``,
        `#### 💡 改善提案`,
        ``,
        `${sugContent}${factSection}`,
        `---`,
        ``,
      ].join("\n");
    });

    lines.push(...details);
  }

  return lines.join("\n");
}

// ============================================================================
// Main Execution
// ============================================================================

function main() {
  const { includeFacts, force, sessionId, customReportPath, customOutputPath } =
    parseCliArgs();

  const { reportPath, outputPath } = resolveFilePaths(
    customReportPath,
    customOutputPath,
    sessionId,
  );

  console.log(`Input Report:  ${reportPath}`);
  console.log(`Output Issues: ${outputPath}`);

  const lines = fs.readFileSync(reportPath, "utf8").split(/\r?\n/);

  const { tableHeaders, extractedRows, targetPhases, phaseMeta } =
    parseQualitySummaryTable(lines);
  const detailBlocks = parseDetailPhaseBlocks(lines);

  const finalContent = buildIssuesMarkdown(
    reportPath,
    tableHeaders,
    extractedRows,
    targetPhases,
    phaseMeta,
    detailBlocks,
    includeFacts,
  );

  fs.writeFileSync(outputPath, finalContent, "utf8");

  console.log("Extraction completed successfully.");
  console.log(`Output path: ${outputPath}`);
  console.log(`Extracted Issues Count: ${targetPhases.length}`);
}

if (require.main === module) {
  main();
}

module.exports = {
  normalizePhaseKey,
  classifySectionHeading,
  isProblematicPhase,
  parseQualitySummaryTable,
  parseDetailPhaseBlocks,
  buildIssuesMarkdown,
};
