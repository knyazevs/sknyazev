// Генерация статических JSON + контентных файлов для прод-билда.
// Заменяет runtime-запросы к api.github.com: во время CI-билда мы
// читаем репозиторий с диска и кладём результат в app/public/data/,
// откуда он уходит в app/dist/data/ при сборке Astro.

import fs from 'node:fs';
import path from 'node:path';
import {
  REPO_ROOT,
  buildTree,
  buildCodeTree,
  buildCommits,
  collectAllCodePaths,
  getContent,
  getCodeContent,
} from '../app/src/lib/docs-data.ts';

const DATA_ROOT = path.join(REPO_ROOT, 'app', 'public', 'data');
const CONTENT_ROOT = path.join(DATA_ROOT, 'content');
const COMMITS_LIMIT = 200;

function ensureDir(dir: string) {
  fs.mkdirSync(dir, { recursive: true });
}

function writeJson(file: string, data: unknown) {
  ensureDir(path.dirname(file));
  fs.writeFileSync(file, JSON.stringify(data));
}

function writeContentFile(relPath: string, content: string) {
  const abs = path.join(CONTENT_ROOT, relPath);
  ensureDir(path.dirname(abs));
  fs.writeFileSync(abs, content);
}

function resetDataDir() {
  if (fs.existsSync(DATA_ROOT)) {
    fs.rmSync(DATA_ROOT, { recursive: true, force: true });
  }
  ensureDir(DATA_ROOT);
}

function collectAllDocPaths(): string[] {
  const paths: string[] = [];
  for (const section of buildTree()) {
    if (section.readmePath) paths.push(section.readmePath);
    for (const item of section.items) paths.push(item.path);
  }
  return paths;
}

function main() {
  console.log('[static-data] очищаю', path.relative(REPO_ROOT, DATA_ROOT));
  resetDataDir();

  console.log('[static-data] docs-tree.json');
  const tree = buildTree();
  writeJson(path.join(DATA_ROOT, 'docs-tree.json'), { sections: tree });

  console.log('[static-data] code-tree.json');
  const codeTree = buildCodeTree();
  writeJson(path.join(DATA_ROOT, 'code-tree.json'), { dirs: codeTree });

  console.log(`[static-data] commits.json (last ${COMMITS_LIMIT})`);
  const commits = buildCommits(COMMITS_LIMIT);
  writeJson(path.join(DATA_ROOT, 'commits.json'), { commits });

  const docPaths = collectAllDocPaths();
  console.log(`[static-data] docs content: ${docPaths.length} files`);
  for (const p of docPaths) {
    const content = getContent(p);
    if (content === null) {
      console.warn(`[static-data] пропуск: не найден ${p}`);
      continue;
    }
    writeContentFile(p, content);
  }

  const codePaths = collectAllCodePaths();
  console.log(`[static-data] code content: ${codePaths.length} files`);
  for (const p of codePaths) {
    const content = getCodeContent(p);
    if (content === null) {
      console.warn(`[static-data] пропуск: не найден ${p}`);
      continue;
    }
    writeContentFile(p, content);
  }

  console.log('[static-data] готово');
}

main();
