#!/usr/bin/env node
import {
  existsSync,
  readdirSync,
  lstatSync,
  statSync,
  copyFileSync,
  realpathSync,
  rmSync,
  symlinkSync,
  unlinkSync,
  mkdirSync,
  readlinkSync,
  readFileSync,
  writeFileSync,
} from "fs";
import { join, dirname } from "path";
import { fileURLToPath } from "url";
import { parseArgs } from "node:util";
import { checkHelp, ensureDirSync } from "./common.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const HELP_TEXT = `
setup.js - AI Agents セットアップツール

【概要】
  プロジェクトルートの .agents ディレクトリに workflows, skills, bin, hooks, rules, hooks.json を
  シンボリックリンク（またはコピー）して同期・セットアップします。
  また、Claude Code 向けに CLAUDE.md のインクルード設定、.claude/skills/ へのスキルコピー、
  および .claude/commands/ へのワークフロー配置を行います。

【使用法】
  node .agents/bin/setup.js [オプション]

【オプション】
  -c, --copy              シンボリックリンクではなく実ファイルとしてコピー同期
  -h, --help, /?, /help   このヘルプメッセージを表示
`;

function copyFolderRecursiveSync(source, target) {
  ensureDirSync(target);
  const files = readdirSync(source);
  for (const file of files) {
    const curSource = join(source, file);
    const curTarget = join(target, file);
    try {
      const stat = statSync(curSource);
      if (stat.isDirectory()) {
        copyFolderRecursiveSync(curSource, curTarget);
      } else {
        copyFileSync(curSource, curTarget);
      }
    } catch (_) {
      // Skip inaccessible or broken links
    }
  }
}

function cleanDanglingSymlinks(targetDir, sourceDir) {
  if (!existsSync(targetDir)) return;
  const targetFiles = readdirSync(targetDir);
  for (const file of targetFiles) {
    const targetPath = join(targetDir, file);
    const sourcePath = join(sourceDir, file);
    try {
      const stat = lstatSync(targetPath);
      if (stat.isSymbolicLink()) {
        // If it's a symlink, check if the corresponding file still exists in the source
        if (!existsSync(sourcePath)) {
          console.log(`  Cleaning up removed symlink: ${file}`);
          unlinkSync(targetPath);
        } else {
          // Check if link is broken (target doesn't exist)
          try {
            const realPath = realpathSync(targetPath);
            if (!existsSync(realPath)) {
              console.log(`  Cleaning up broken symlink: ${file}`);
              unlinkSync(targetPath);
            }
          } catch (e) {
            console.log(`  Cleaning up broken symlink: ${file}`);
            unlinkSync(targetPath);
          }
        }
      } else if (stat.isDirectory()) {
        // Recursively clean up subdirectories
        cleanDanglingSymlinks(targetPath, sourcePath);
      }
    } catch (e) {
      // Ignore errors reading stat
    }
  }
}

function syncFile(sourceFile, targetFile, forceCopy, managedFiles, projectRoot) {
  if (!existsSync(sourceFile)) return;

  const targetDir = dirname(targetFile);
  ensureDirSync(targetDir);

  if (existsSync(targetFile)) {
    try {
      const targetStat = lstatSync(targetFile);
      if (targetStat.isSymbolicLink()) {
        unlinkSync(targetFile);
      } else if (forceCopy) {
        unlinkSync(targetFile);
      } else {
        // If it's an existing real file (e.g. customized hooks.json), check if we can merge
        if (targetFile.endsWith("hooks.json")) {
          try {
            const existingJson = JSON.parse(readFileSync(targetFile, "utf-8"));
            const sourceJson = JSON.parse(readFileSync(sourceFile, "utf-8"));
            // Merge top-level keys
            let merged = false;
            for (const key of Object.keys(sourceJson)) {
              if (!existingJson[key]) {
                existingJson[key] = sourceJson[key];
                merged = true;
              }
            }
            if (merged) {
              writeFileSync(targetFile, JSON.stringify(existingJson, null, 2) + "\n", "utf-8");
              console.log(`  Merged hooks from package into existing ${targetFile}`);
            }
            if (managedFiles && projectRoot) {
              let relPath = targetFile.substring(projectRoot.length);
              if (relPath.startsWith("/") || relPath.startsWith("\\")) {
                relPath = relPath.substring(1);
              }
              managedFiles.push(relPath);
            }
            return;
          } catch (_) {
            // If json parse failed, replace it
            rmSync(targetFile, { force: true });
          }
        } else {
          rmSync(targetFile, { force: true });
        }
      }
    } catch (e) {
      rmSync(targetFile, { force: true });
    }
  }

  if (forceCopy) {
    copyFileSync(sourceFile, targetFile);
  } else {
    try {
      symlinkSync(sourceFile, targetFile, "file");
    } catch (err) {
      copyFileSync(sourceFile, targetFile);
    }
  }

  if (managedFiles && projectRoot) {
    let relPath = targetFile.substring(projectRoot.length);
    if (relPath.startsWith("/") || relPath.startsWith("\\")) {
      relPath = relPath.substring(1);
    }
    managedFiles.push(relPath);
  }
}

function syncRecursive(
  sourceDir,
  targetDir,
  forceCopy,
  managedFiles,
  projectRoot,
) {
  ensureDirSync(targetDir);

  // Clean up dangling symlinks first
  cleanDanglingSymlinks(targetDir, sourceDir);

  const files = readdirSync(sourceDir);
  for (const file of files) {
    const curSource = join(sourceDir, file);
    const curTarget = join(targetDir, file);

    const stat = lstatSync(curSource);
    if (stat.isDirectory()) {
      syncRecursive(curSource, curTarget, forceCopy, managedFiles, projectRoot);
    } else {
      // It's a file
      if (existsSync(curTarget)) {
        try {
          const targetStat = lstatSync(curTarget);
          if (targetStat.isSymbolicLink()) {
            unlinkSync(curTarget);
          } else if (forceCopy) {
            // Overwrite if forceCopy is on
            unlinkSync(curTarget);
          } else {
            // If a real file exists and forceCopy is false, we might want to warn or overwrite?
            // Since it's a system file that should be a symlink, we should replace it to ensure it's synced.
            rmSync(curTarget, { force: true });
          }
        } catch (e) {
          rmSync(curTarget, { force: true });
        }
      }

      if (forceCopy) {
        copyFileSync(curSource, curTarget);
      } else {
        try {
          symlinkSync(curSource, curTarget, "file");
        } catch (err) {
          copyFileSync(curSource, curTarget);
        }
      }

      // Track managed file for .gitignore
      if (managedFiles && projectRoot) {
        let relPath = curTarget.substring(projectRoot.length);
        if (relPath.startsWith("/") || relPath.startsWith("\\")) {
          relPath = relPath.substring(1);
        }
        managedFiles.push(relPath);
      }
    }
  }
}

function syncDirectory(
  sourceDir,
  targetDir,
  dirName,
  managedFiles,
  projectRoot,
  forceCopy = false,
) {
  console.log(`Syncing ${dirName}...`);
  console.log(`  Source: ${sourceDir}`);
  console.log(`  Target: ${targetDir}`);

  let realSourceDir = sourceDir;
  try {
    if (existsSync(sourceDir)) {
      realSourceDir = realpathSync(sourceDir);
    }
  } catch (_) {}

  // Ensure targetDir is a real directory, not a symlink
  try {
    const targetStat = lstatSync(targetDir);
    if (targetStat.isSymbolicLink()) {
      console.log(`  Converting legacy directory symlink to real directory...`);
      unlinkSync(targetDir);
      ensureDirSync(targetDir);
    }
  } catch (_) {
    // Doesn't exist, will be created
  }

  try {
    syncRecursive(
      realSourceDir,
      targetDir,
      forceCopy,
      managedFiles,
      projectRoot,
    );
    console.log(`  Successfully synced ${dirName} directory.`);
  } catch (err) {
    console.error(`  Failed to sync ${dirName}: ${err.message}`);
  }
}

function updateGitignore(projectRoot, managedFiles) {
  if (!managedFiles || managedFiles.length === 0) return;

  const gitignorePath = join(projectRoot, ".gitignore");
  let content = "";
  if (existsSync(gitignorePath)) {
    content = readFileSync(gitignorePath, "utf-8");
  }

  const startMarker = "# --- BEGIN Antigravity global AGENTS MANAGED FILES ---";
  const endMarker = "# --- END Antigravity global AGENTS MANAGED FILES ---";

  // Group skills, bin, and hooks into directory entries to avoid individual file leakage,
  // while keeping workflows and rules as individual files for project-level customization.
  const ignoreEntries = new Set();
  for (const rawFile of managedFiles) {
    const f = rawFile.replace(/\\/g, "/");
    if (f.startsWith(".agents/skills/")) {
      const parts = f.split("/");
      // parts: ['.agents', 'skills', '<skillName>', ...]
      if (parts.length >= 3) {
        ignoreEntries.add(`/${parts[0]}/${parts[1]}/${parts[2]}/`);
        continue;
      }
    } else if (f.startsWith(".claude/skills/")) {
      const parts = f.split("/");
      // parts: ['.claude', 'skills', '<skillName>', ...]
      if (parts.length >= 3) {
        ignoreEntries.add(`/${parts[0]}/${parts[1]}/${parts[2]}/`);
        continue;
      }
    } else if (f.startsWith(".agents/bin/")) {
      ignoreEntries.add("/.agents/bin/");
      continue;
    } else if (f.startsWith(".agents/hooks/")) {
      ignoreEntries.add("/.agents/hooks/");
      continue;
    }
    ignoreEntries.add("/" + f);
  }

  const linesToAdd = Array.from(ignoreEntries).sort();
  const block = [
    startMarker,
    "# These files are auto-synced by setup.js. DO NOT EDIT MANUALLY.",
    ...linesToAdd,
    endMarker,
  ].join("\n");

  const startIndex = content.indexOf(startMarker);
  const endIndex = content.indexOf(endMarker);

  if (startIndex !== -1 && endIndex !== -1 && endIndex > startIndex) {
    // Replace existing block
    content =
      content.substring(0, startIndex) +
      block +
      content.substring(endIndex + endMarker.length);
  } else {
    // Append to end
    if (content.length > 0 && !content.endsWith("\n")) {
      content += "\n";
    }
    content += "\n" + block + "\n";
  }

  writeFileSync(gitignorePath, content, "utf-8");
  console.log(`Updated .gitignore with ${linesToAdd.length} managed entries.`);
}

function setupClaudeIntegration(projectRoot, targetAgentsDir, managedFiles) {
  console.log(`Setting up Claude integration...`);

  // 1. Copy skills from .agents/skills/ to .claude/skills/
  const agentsSkillsDir = join(targetAgentsDir, "skills");
  const claudeSkillsDir = join(projectRoot, ".claude", "skills");

  if (existsSync(agentsSkillsDir)) {
    ensureDirSync(claudeSkillsDir);
    const skillDirs = readdirSync(agentsSkillsDir);

    for (const skillName of skillDirs) {
      const sourceSkillPath = join(agentsSkillsDir, skillName);
      const targetSkillPath = join(claudeSkillsDir, skillName);

      try {
        let isDir = false;
        try {
          isDir = statSync(sourceSkillPath).isDirectory();
        } catch (_) {}

        if (isDir) {
          if (existsSync(targetSkillPath)) {
            rmSync(targetSkillPath, { recursive: true, force: true });
          }
          copyFolderRecursiveSync(sourceSkillPath, targetSkillPath);
          console.log(`  Copied skill to Claude: ${skillName}`);

          if (managedFiles && projectRoot) {
            let relPath = targetSkillPath.substring(projectRoot.length);
            if (relPath.startsWith("/") || relPath.startsWith("\\")) {
              relPath = relPath.substring(1);
            }
            managedFiles.push(relPath);
          }
        }
      } catch (err) {
        console.error(`  Failed to copy skill ${skillName} to Claude: ${err.message}`);
      }
    }
  }

  // 2. Copy workflows from .agents/workflows/ to .claude/commands/
  const agentsWorkflowsDir = join(targetAgentsDir, "workflows");
  const claudeCommandsDir = join(projectRoot, ".claude", "commands");

  if (existsSync(agentsWorkflowsDir)) {
    ensureDirSync(claudeCommandsDir);
    const workflowEntries = readdirSync(agentsWorkflowsDir);

    for (const entry of workflowEntries) {
      const sourceWorkflowPath = join(agentsWorkflowsDir, entry);
      const targetWorkflowPath = join(claudeCommandsDir, entry);

      try {
        let stat;
        try {
          stat = statSync(sourceWorkflowPath);
        } catch (_) {}

        if (stat) {
          if (stat.isDirectory()) {
            if (existsSync(targetWorkflowPath)) {
              rmSync(targetWorkflowPath, { recursive: true, force: true });
            }
            copyFolderRecursiveSync(sourceWorkflowPath, targetWorkflowPath);
          } else {
            if (existsSync(targetWorkflowPath)) {
              unlinkSync(targetWorkflowPath);
            }
            copyFileSync(sourceWorkflowPath, targetWorkflowPath);
          }
          console.log(`  Copied workflow to Claude command: ${entry}`);

          if (managedFiles && projectRoot) {
            let relPath = targetWorkflowPath.substring(projectRoot.length);
            if (relPath.startsWith("/") || relPath.startsWith("\\")) {
              relPath = relPath.substring(1);
            }
            managedFiles.push(relPath);
          }
        }
      } catch (err) {
        console.error(`  Failed to copy workflow ${entry} to Claude: ${err.message}`);
      }
    }
  }

  // 3. Generate or update CLAUDE.md
  const claudeMdPath = join(projectRoot, "CLAUDE.md");
  const agentsRulesDir = join(targetAgentsDir, "rules");

  const ruleIncludes = [];
  if (existsSync(agentsRulesDir)) {
    const ruleFiles = readdirSync(agentsRulesDir);
    for (const file of ruleFiles) {
      if (file.endsWith(".md")) {
        ruleIncludes.push(`@.agents/rules/${file}`);
      }
    }
    ruleIncludes.sort();
  }

  const startMarker = "<!-- BEGIN Antigravity global AGENTS MANAGED -->";
  const endMarker = "<!-- END Antigravity global AGENTS MANAGED -->";

  const managedLines = [
    startMarker,
    "@AGENTS.md",
    ...ruleIncludes,
    endMarker,
  ];
  const managedContent = managedLines.join("\n");

  let existingContent = "";
  if (existsSync(claudeMdPath)) {
    existingContent = readFileSync(claudeMdPath, "utf-8");
  }

  const startIndex = existingContent.indexOf(startMarker);
  const endIndex = existingContent.indexOf(endMarker);

  let newContent = "";
  if (startIndex !== -1 && endIndex !== -1 && endIndex > startIndex) {
    newContent =
      existingContent.substring(0, startIndex) +
      managedContent +
      existingContent.substring(endIndex + endMarker.length);
  } else if (existingContent.trim().length > 0) {
    newContent = existingContent.trimEnd() + "\n\n" + managedContent + "\n";
  } else {
    newContent = managedContent + "\n";
  }

  writeFileSync(claudeMdPath, newContent, "utf-8");
  console.log(`  Updated ${claudeMdPath} with @AGENTS.md and rules.`);
}

function main() {
  checkHelp(HELP_TEXT);

  const options = {
    copy: { type: "boolean", short: "c", default: false },
    help: { type: "boolean", short: "h", default: false },
  };

  const { values } = parseArgs({
    options,
    allowPositionals: true,
    strict: false,
  });

  const forceCopy = values.copy;
  const projectRoot = process.cwd();
  const packageDir = join(__dirname, "..");
  const distDir = join(packageDir, "dist");
  const targetAgentsDir = join(projectRoot, ".agents");

  console.log(`Setting up Antigravity Global AI Agents...`);
  ensureDirSync(targetAgentsDir);

  const managedFiles = [];

  // Automatically sync all directories and files in dist/
  if (existsSync(distDir)) {
    const entries = readdirSync(distDir);
    for (const entry of entries) {
      const sourcePath = join(distDir, entry);
      const targetPath = join(targetAgentsDir, entry);
      const stat = lstatSync(sourcePath);

      if (stat.isDirectory()) {
        syncDirectory(
          sourcePath,
          targetPath,
          entry,
          managedFiles,
          projectRoot,
          forceCopy,
        );
      } else {
        console.log(`Syncing ${entry}...`);
        syncFile(
          sourcePath,
          targetPath,
          forceCopy,
          managedFiles,
          projectRoot,
        );
      }
    }
  }

  // Also sync bin directory from package root
  const binSource = join(packageDir, "bin");
  const binTarget = join(targetAgentsDir, "bin");
  if (existsSync(binSource)) {
    syncDirectory(
      binSource,
      binTarget,
      "bin",
      managedFiles,
      projectRoot,
      forceCopy,
    );
  }

  // Setup Claude integration (CLAUDE.md & .claude/skills/)
  setupClaudeIntegration(projectRoot, targetAgentsDir, managedFiles);

  updateGitignore(projectRoot, managedFiles);
}

main();

export { syncDirectory, syncFile, copyFolderRecursiveSync, setupClaudeIntegration };
export default {
  syncDirectory,
  syncFile,
  copyFolderRecursiveSync,
  setupClaudeIntegration,
};
