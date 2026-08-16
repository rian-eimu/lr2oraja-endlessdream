#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const { checkHelp, ensureDirSync } = require("./common");

const HELP_TEXT = `
setup.js - Tilly AI Agents セットアップツール

【概要】
  プロジェクトルートの .agents ディレクトリに workflows, skills, bin を
  シンボリックリンク（またはコピー）して同期・セットアップします。

【使用法】
  node .agents/bin/setup.js [オプション]

【オプション】
  -h, --help, /?, /help   このヘルプメッセージを表示
`;

function copyFolderRecursiveSync(source, target) {
  ensureDirSync(target);
  const files = fs.readdirSync(source);
  for (const file of files) {
    const curSource = path.join(source, file);
    const curTarget = path.join(target, file);
    if (fs.lstatSync(curSource).isDirectory()) {
      copyFolderRecursiveSync(curSource, curTarget);
    } else {
      fs.copyFileSync(curSource, curTarget);
    }
  }
}

function syncDirectory(sourceDir, targetDir, dirName) {
  console.log(`Syncing ${dirName}...`);
  console.log(`  Source: ${sourceDir}`);
  console.log(`  Target: ${targetDir}`);

  let realSourceDir = sourceDir;
  try {
    if (fs.existsSync(sourceDir)) {
      realSourceDir = fs.realpathSync(sourceDir);
    }
  } catch (_) {}

  let targetExistsOnDisk = false;
  try {
    fs.lstatSync(targetDir);
    targetExistsOnDisk = true;
  } catch (_) {
    targetExistsOnDisk = false;
  }

  if (targetExistsOnDisk) {
    try {
      fs.rmSync(targetDir, { recursive: true, force: true });
    } catch (e) {
      console.warn(
        `Warning: Could not remove existing target ${targetDir}: ${e.message}`,
      );
    }
  }

  try {
    const type = process.platform === "win32" ? "junction" : "dir";
    fs.symlinkSync(realSourceDir, targetDir, type);
    console.log(`  Successfully symlinked ${dirName} directory (${type}).`);
  } catch (err) {
    console.warn(
      `  Failed to create symlink for ${dirName}: ${err.message}. Falling back to copying files...`,
    );
    copyFolderRecursiveSync(realSourceDir, targetDir);
    console.log(`  Successfully copied ${dirName} directory.`);
  }
}

function main() {
  checkHelp(HELP_TEXT);

  const projectRoot = process.cwd();
  const packageDir = path.join(__dirname, "..");
  const targetAgentsDir = path.join(projectRoot, ".agents");

  console.log(`Setting up Tilly AI Agents...`);
  ensureDirSync(targetAgentsDir);

  const dirsToSync = ["workflows", "skills", "bin"];

  for (const dir of dirsToSync) {
    const sourceDir = path.join(packageDir, dir);
    const targetDir = path.join(targetAgentsDir, dir);

    if (!fs.existsSync(sourceDir)) {
      continue;
    }

    syncDirectory(sourceDir, targetDir, dir);
  }
}

if (require.main === module) {
  main();
}

module.exports = {
  syncDirectory,
  copyFolderRecursiveSync,
};
