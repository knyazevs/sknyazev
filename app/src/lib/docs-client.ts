import { GITHUB, EXCLUDE_FILES, DIR_LABELS, FILE_LABELS } from '../config.js';

export interface DocItem {
  path: string;
  label: string;
}

export interface DocSection {
  id: string;
  label: string;
  items: DocItem[];
  readmePath?: string;
}

// ─── Label helpers ────────────────────────────────────────────────────────────

function labelFromFilename(filename: string, dirId?: string): string {
  const base = filename.replace(/\.md$/i, '');
  if (FILE_LABELS[base]) return FILE_LABELS[base];
  const numbered = base.match(/^(\d+)-(.+)$/);
  if (numbered) {
    const num = parseInt(numbered[1], 10);
    const title = numbered[2].replace(/-/g, ' ');
    if (dirId === 'adr') return `ADR-${num} — ${title}`;
    return title.charAt(0).toUpperCase() + title.slice(1);
  }
  return base.replace(/-/g, ' ');
}

// ─── Dev: local Vite API ──────────────────────────────────────────────────────

async function fetchLocalTree(): Promise<DocSection[]> {
  const res = await fetch('/api/docs/tree');
  const data = await res.json();
  return data.sections;
}

async function fetchLocalContent(filePath: string): Promise<string> {
  const res = await fetch(`/api/docs/content?path=${encodeURIComponent(filePath)}`);
  const data = await res.json();
  if (!res.ok) throw new Error(data.error);
  return data.content;
}

// ─── Prod: GitHub API ─────────────────────────────────────────────────────────

interface GHItem {
  name: string;
  path: string;
  type: 'file' | 'dir';
}

async function githubContents(repoPath: string): Promise<GHItem[]> {
  const { owner, repo } = GITHUB;
  const url = `https://api.github.com/repos/${owner}/${repo}/contents/${repoPath}`;
  const res = await fetch(url, {
    headers: { Accept: 'application/vnd.github.v3+json' },
  });
  if (!res.ok) throw new Error(`GitHub API error: ${res.status}`);
  return res.json();
}

async function fetchGitHubTree(): Promise<DocSection[]> {
  const sections: DocSection[] = [];

  // Содержимое docs/
  let docsItems: GHItem[];
  try {
    docsItems = await githubContents('docs');
  } catch {
    return sections;
  }

  const dirs = docsItems
    .filter(i => i.type === 'dir')
    .sort((a, b) => a.name.localeCompare(b.name));

  await Promise.all(
    dirs.map(async dir => {
      const files = await githubContents(dir.path);
      const readme = files.find(f => f.type === 'file' && f.name === 'README.md');
      const mdFiles = files
        .filter(f => f.type === 'file' && f.name.endsWith('.md') && f.name !== 'README.md' && !EXCLUDE_FILES.includes(f.name))
        .sort((a, b) => a.name.localeCompare(b.name));

      if (mdFiles.length === 0 && !readme) return;

      sections.push({
        id: dir.name,
        label: DIR_LABELS[dir.name] ?? dir.name,
        items: mdFiles.map(f => ({
          path: f.path,
          label: labelFromFilename(f.name, dir.name),
        })),
        readmePath: readme ? readme.path : undefined,
      });
    })
  );

  // Профиль первым, остальные по алфавиту
  return sections.sort((a, b) => {
    if (a.id === 'profile') return -1;
    if (b.id === 'profile') return 1;
    return a.id.localeCompare(b.id);
  });
}

async function fetchGitHubContent(filePath: string): Promise<string> {
  const { owner, repo, branch } = GITHUB;
  const url = `https://raw.githubusercontent.com/${owner}/${repo}/${branch}/${filePath}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Not found: ${filePath}`);
  return res.text();
}

// ─── Public API (docs) ───────────────────────────────────────────────────────

export async function getDocTree(): Promise<DocSection[]> {
  if (import.meta.env.DEV) {
    return fetchLocalTree();
  }
  return fetchGitHubTree();
}

export async function getDocContent(filePath: string): Promise<string> {
  if (import.meta.env.DEV) {
    return fetchLocalContent(filePath);
  }
  return fetchGitHubContent(filePath);
}

// ─── Public API (code) ───────────────────────────────────────────────────────

export interface CodeFile {
  path: string;
  name: string;
  ext: string;
  size: number;
}

export interface CodeDir {
  path: string;
  name: string;
  children: (CodeDir | CodeFile)[];
}

export async function getCodeTree(): Promise<CodeDir[]> {
  if (import.meta.env.DEV) {
    const res = await fetch('/api/code/tree');
    const data = await res.json();
    return data.dirs;
  }
  // Prod: GitHub API — fetch tree for each source dir
  return fetchGitHubCodeTree();
}

export async function getCodeContent(filePath: string): Promise<{ content: string; ext: string }> {
  if (import.meta.env.DEV) {
    const res = await fetch(`/api/code/content?path=${encodeURIComponent(filePath)}`);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error);
    return { content: data.content, ext: data.ext };
  }
  // Prod: raw GitHub
  const { owner, repo, branch } = GITHUB;
  const url = `https://raw.githubusercontent.com/${owner}/${repo}/${branch}/${filePath}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Not found: ${filePath}`);
  const content = await res.text();
  const ext = filePath.split('.').pop() ?? '';
  return { content, ext };
}

async function fetchGitHubCodeTree(): Promise<CodeDir[]> {
  const { owner, repo } = GITHUB;
  const sourceDirs = ['server/src', 'app/src', 'scripts'];
  const results: CodeDir[] = [];

  for (const dir of sourceDirs) {
    try {
      const url = `https://api.github.com/repos/${owner}/${repo}/git/trees/HEAD:${dir}?recursive=1`;
      const res = await fetch(url, { headers: { Accept: 'application/vnd.github.v3+json' } });
      if (!res.ok) continue;
      const data = await res.json();
      results.push(githubTreeToCodeDir(data.tree, dir));
    } catch { /* skip */ }
  }

  return results;
}

function githubTreeToCodeDir(tree: Array<{ path: string; type: string; size?: number }>, rootDir: string): CodeDir {
  const root: CodeDir = { path: rootDir, name: rootDir.split('/').pop()!, children: [] };
  const dirs = new Map<string, CodeDir>();
  dirs.set('', root);

  const INCLUDED_EXT = new Set(['kt', 'kts', 'ts', 'mjs', 'js', 'svelte', 'toml', 'yml', 'yaml', 'json']);

  for (const item of tree) {
    const ext = item.path.split('.').pop() ?? '';
    if (item.type === 'blob' && !INCLUDED_EXT.has(ext)) continue;

    const parts = item.path.split('/');
    const parentPath = parts.slice(0, -1).join('/');
    const name = parts[parts.length - 1];

    // Ensure parent dirs exist
    let current = '';
    for (const part of parts.slice(0, -1)) {
      const prev = current;
      current = current ? current + '/' + part : part;
      if (!dirs.has(current)) {
        const dir: CodeDir = { path: rootDir + '/' + current, name: part, children: [] };
        dirs.get(prev)!.children.push(dir);
        dirs.set(current, dir);
      }
    }

    if (item.type === 'blob') {
      dirs.get(parentPath)!.children.push({
        path: rootDir + '/' + item.path,
        name,
        ext,
        size: item.size ?? 0,
      });
    }
  }

  return root;
}
