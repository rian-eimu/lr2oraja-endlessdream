#!/usr/bin/env node
const fs = require("fs");
const path = require("path");

function copyFolderRecursiveSync(source, target) {
  if (!fs.existsSync(target)) {
    fs.mkdirSync(target, { recursive: true });
  }
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

  // Clean up existing target if it exists
  if (fs.existsSync(targetDir)) {
    try {
      const lstat = fs.lstatSync(targetDir);
      if (lstat.isSymbolicLink() || lstat.isDirectory()) {
        fs.rmSync(targetDir, { recursive: true, force: true });
      }
    } catch (e) {
      console.warn(
        `Warning: Could not remove existing directory ${targetDir}: ${e.message}`,
      );
    }
  }

  // Try creating a symlink (junction on Windows, symlink on other platforms)
  try {
    const type = process.platform === "win32" ? "junction" : "dir";
    fs.symlinkSync(sourceDir, targetDir, type);
    console.log(`  Successfully symlinked ${dirName} directory (${type}).`);
  } catch (err) {
    console.warn(
      `  Failed to create symlink for ${dirName}: ${err.message}. Falling back to copying files...`,
    );
    // Fallback to copy
    copyFolderRecursiveSync(sourceDir, targetDir);
    console.log(`  Successfully copied ${dirName} directory.`);
  }
}

function main() {
  const projectRoot = process.cwd();
  const packageDir = path.join(__dirname, "..");
  const targetAgentsDir = path.join(projectRoot, ".agents");

  console.log(`Setting up Tilly AI Agents...`);

  // Ensure .agents directory exists
  if (!fs.existsSync(targetAgentsDir)) {
    fs.mkdirSync(targetAgentsDir, { recursive: true });
  }

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

main();
