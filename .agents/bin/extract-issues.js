#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const { parseArgs } = require("node:util");

function parseArguments() {
  const options = {
    "include-facts": { type: "boolean", default: false },
    force: { type: "boolean", default: false },
    "session-id": { type: "string", short: "s", default: "" },
  };
  const { values, positionals } = parseArgs({
    options,
    allowPositionals: true,
  });
  return {
    includeFacts: values["include-facts"],
    force: values.force,
    sessionId: values["session-id"],
    customReportPath: positionals[0] || "",
    customOutputPath: positionals[1] || "",
  };
}

function findTargetReport(defaultDir, sessionId) {
  if (!fs.existsSync(defaultDir)) {
    console.error(`Error: Default directory not found at ${defaultDir}`);
    process.exit(1);
  }
  const files = fs.readdirSync(defaultDir);
  const matched = files
    .filter(
      (f) => f.includes("-integrated-review-report_") && f.endsWith(".md"),
    )
    .filter((f) => !sessionId || f.startsWith(`${sessionId}-`))
    .map((f) => ({
      name: f,
      time: fs.statSync(path.join(defaultDir, f)).mtimeMs,
    }))
    .sort((a, b) => b.time - a.time);

  if (matched.length === 0) {
    console.error(
      `Error: No integrated review report found${sessionId ? ` for session ${sessionId}` : ""}.`,
    );
    process.exit(1);
  }
  return path.join(defaultDir, matched[0].name);
}

function resolvePaths(customReportPath, customOutputPath, sessionId) {
  const defaultDir = path.join(__dirname, "../../tmp/code-reviews");
  let reportPath = customReportPath;

  if (!reportPath) {
    reportPath = findTargetReport(defaultDir, sessionId);
  } else {
    if (!fs.existsSync(reportPath)) {
      console.error(`Error: Input report file not found: ${reportPath}`);
      process.exit(1);
    }
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

function testIsIssue(facts, gateResult) {
  if (/\*\*F\*\*:\s*([1-9]\d*)/.test(facts)) return true;
  if (/\*\*M\*\*:\s*([1-9]\d*)/.test(facts)) return true;
  if (/\*\*m\*\*:\s*([1-9]\d*)/.test(facts)) return true;
  if (facts.includes("RED CARD") || facts.includes("FAIL")) return true;
  if (gateResult.includes("FAIL") || gateResult.includes("💀")) return true;
  return false;
}

function parseQualitySummary(lines) {
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
      (/^\|\s*フェーズ\s*\|/.test(line) || /^\|\s*.*Gate 1/.test(line))
    ) {
      inTable = true;
      tableHeaders.push(line);
      continue;
    }
    if (!inTable) continue;
    if (/^\|\s*:?---\s*:?/.test(line)) {
      tableHeaders.push(line);
      continue;
    }
    if (!line.startsWith("|")) continue;

    const columns = line.split("|");
    if (columns.length < 6) continue;

    const phaseName = columns[1].trim().replace(/\*\*|\*/g, "");
    const title = columns[2].trim();
    const score = columns[3].trim();
    const gateResult = columns[4].trim();
    const facts = columns[5].trim();

    if (testIsIssue(facts, gateResult)) {
      extractedRows.push(line);
      targetPhases.push(phaseName);
      phaseMeta[phaseName] = { title, score, gateResult, facts };
    }
  }

  return { tableHeaders, extractedRows, targetPhases, phaseMeta };
}

function getSectionType(rawName) {
  if (
    rawName.includes("脆弱性") ||
    rawName.includes("リファクタリング") ||
    (rawName.includes("改善提案") && !rawName.includes("テスト戦略"))
  ) {
    return "Suggestions";
  }
  if (rawName.includes("評価プロセス") || rawName.includes("ファクト")) {
    return "Facts";
  }
  return null;
}

function extractDetailBlocks(lines) {
  const detailBlocks = {};
  let currentPhase = null;
  let currentSection = null;
  let sectionBuffer = [];

  for (const line of lines) {
    const phaseMatch = line.match(/^#\s*【(Phase\s*\d+-\d+)】/);
    if (phaseMatch) {
      if (currentPhase && currentSection) {
        detailBlocks[currentPhase][currentSection] = sectionBuffer
          .join("\n")
          .trim();
      }
      currentPhase = phaseMatch[1];
      if (!detailBlocks[currentPhase]) {
        detailBlocks[currentPhase] = {};
      }
      currentSection = null;
      sectionBuffer = [];
      continue;
    }

    if (!currentPhase) continue;

    const sectionMatch = line.match(/^##\s*(.*)$/);
    if (sectionMatch) {
      if (currentSection) {
        detailBlocks[currentPhase][currentSection] = sectionBuffer
          .join("\n")
          .trim();
      }
      currentSection = getSectionType(sectionMatch[1]);
      sectionBuffer = [];
      continue;
    }

    if (line.startsWith("---")) {
      if (currentSection) {
        detailBlocks[currentPhase][currentSection] = sectionBuffer
          .join("\n")
          .trim();
      }
      currentPhase = null;
      currentSection = null;
      sectionBuffer = [];
      continue;
    }

    if (currentSection) {
      sectionBuffer.push(line);
    }
  }

  if (currentPhase && currentSection) {
    detailBlocks[currentPhase][currentSection] = sectionBuffer
      .join("\n")
      .trim();
  }

  return detailBlocks;
}

function buildOutputMarkdown(
  reportPath,
  tableHeaders,
  extractedRows,
  targetPhases,
  phaseMeta,
  detailBlocks,
  includeFacts,
) {
  let detailsText =
    "指摘事項（問題ありと判定されたフェーズ）はありませんでした。";
  if (targetPhases.length > 0) {
    const details = targetPhases.map((phase) => {
      const meta = phaseMeta[phase];
      const sugContent =
        (detailBlocks[phase] && detailBlocks[phase]["Suggestions"]) ||
        "具体的な改善提案はありません。";
      let factSection = "";
      if (includeFacts && detailBlocks[phase] && detailBlocks[phase]["Facts"]) {
        factSection = `\n\n#### 📝 検出ファクト\n\n${detailBlocks[phase]["Facts"]}\n`;
      }
      return `### 🔴 [${phase}] ${meta.title}\n\n- **スコア**: ${meta.score}\n- **判定**: ${meta.gateResult}\n- **件数サマリー**: ${meta.facts}\n\n#### 💡 改善提案\n\n${sugContent}${factSection}\n---\n`;
    });
    detailsText = details.join("\n");
  }

  const reportName = path.basename(reportPath);
  return `# 抽出された指摘・改善事項一覧\n\n元レポート: [${reportName}](${reportPath})\n\n## 1. 品質評価サマリー（問題ありフェーズのみ）\n\n${tableHeaders.join("\n")}\n${extractedRows.join("\n")}\n\n---\n\n## 2. 指摘・改善提案の詳細\n\n${detailsText}`;
}

function main() {
  const { includeFacts, force, sessionId, customReportPath, customOutputPath } =
    parseArguments();

  const { reportPath, outputPath } = resolvePaths(
    customReportPath,
    customOutputPath,
    sessionId,
  );

  console.log(`Input Report:  ${reportPath}`);
  console.log(`Output Issues: ${outputPath}`);

  const lines = fs.readFileSync(reportPath, "utf8").split(/\r?\n/);

  const { tableHeaders, extractedRows, targetPhases, phaseMeta } =
    parseQualitySummary(lines);
  const detailBlocks = extractDetailBlocks(lines);

  const finalContent = buildOutputMarkdown(
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

main();
