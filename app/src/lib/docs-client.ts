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

// ─── Public API ───────────────────────────────────────────────────────────────

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
