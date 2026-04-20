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

export interface CodeSearchHit {
  lineNumber: number;
  line: string;
}

export interface CodeSearchResult {
  path: string;
  matches: CodeSearchHit[];
  totalMatches: number;
}

const PROD_SEARCH_MAX_HITS_PER_FILE = 5;
const PROD_SEARCH_LINE_PREVIEW_MAX_LEN = 220;

function collectCodePaths(tree: CodeDir[]): string[] {
  const out: string[] = [];
  const walk = (node: CodeDir | CodeFile) => {
    if ('ext' in node) out.push(node.path);
    else node.children.forEach(walk);
  };
  tree.forEach(walk);
  return out;
}

const prodCodeContentCache = new Map<string, string>();

async function prodFetchAllContents(tree: CodeDir[], onProgress?: () => void): Promise<void> {
  const paths = collectCodePaths(tree).filter(p => !prodCodeContentCache.has(p));
  const BATCH = 8;
  for (let i = 0; i < paths.length; i += BATCH) {
    const batch = paths.slice(i, i + BATCH);
    await Promise.all(batch.map(async p => {
      try {
        const r = await getCodeContent(p);
        prodCodeContentCache.set(p, r.content);
      } catch {
        prodCodeContentCache.set(p, '');
      }
    }));
    onProgress?.();
  }
}

function buildSearchRegex(query: string, isRegex: boolean): RegExp | null {
  try {
    return new RegExp(
      isRegex ? query : query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
      'gi',
    );
  } catch {
    return null;
  }
}

function countLineMatches(line: string, re: RegExp): number {
  re.lastIndex = 0;
  let count = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(line)) !== null) {
    count++;
    if (m[0].length === 0) re.lastIndex++;
  }
  return count;
}

function prodSearchInCache(query: string, isRegex: boolean): CodeSearchResult[] {
  const re = buildSearchRegex(query, isRegex);
  if (!re) return [];
  const results: CodeSearchResult[] = [];
  for (const [filePath, content] of prodCodeContentCache) {
    if (!content) continue;
    const lines = content.split('\n');
    const matches: CodeSearchHit[] = [];
    let total = 0;
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const lineHits = countLineMatches(line, re);
      if (lineHits === 0) continue;
      total += lineHits;
      if (matches.length < PROD_SEARCH_MAX_HITS_PER_FILE) {
        matches.push({
          lineNumber: i + 1,
          line: line.length > PROD_SEARCH_LINE_PREVIEW_MAX_LEN
            ? line.slice(0, PROD_SEARCH_LINE_PREVIEW_MAX_LEN) + '…'
            : line,
        });
      }
    }
    if (total > 0) results.push({ path: filePath, matches, totalMatches: total });
  }
  results.sort((a, b) => b.totalMatches - a.totalMatches);
  return results;
}

export async function searchCode(
  query: string,
  tree: CodeDir[],
  isRegex = false,
  onProgress?: () => void,
): Promise<CodeSearchResult[]> {
  const q = query.trim();
  if (q.length < 2) return [];
  if (import.meta.env.DEV) {
    const url = `/api/code/search?q=${encodeURIComponent(q)}${isRegex ? '&regex=1' : ''}`;
    const res = await fetch(url);
    if (!res.ok) return [];
    const data = await res.json();
    return data.results ?? [];
  }
  await prodFetchAllContents(tree, onProgress);
  return prodSearchInCache(q, isRegex);
}

// ─── Public API (commits) ────────────────────────────────────────────────────

export interface Commit {
  hash: string;
  date: string;
  author: string;
  message: string;
  type: string;
  scope: string;
  body: string;
}

const COMMIT_TYPE_RE = /^(feat|arch|fix|docs|infra|design|refactor)(?:\(([^)]+)\))?:\s*(.+)$/;

function parseCommitMessage(message: string): { type: string; scope: string; body: string } {
  const m = message.match(COMMIT_TYPE_RE);
  if (m) return { type: m[1], scope: m[2] ?? '', body: m[3] };
  return { type: 'other', scope: '', body: message };
}

export async function getCommits(limit = 60): Promise<Commit[]> {
  if (import.meta.env.DEV) {
    const res = await fetch(`/api/commits?limit=${limit}`);
    if (!res.ok) return [];
    const data = await res.json();
    return data.commits ?? [];
  }
  const { owner, repo } = GITHUB;
  const url = `https://api.github.com/repos/${owner}/${repo}/commits?per_page=${limit}`;
  const res = await fetch(url, {
    headers: { Accept: 'application/vnd.github.v3+json' },
  });
  if (!res.ok) return [];
  const data = await res.json() as Array<{
    sha: string;
    commit: { message: string; author: { name: string; date: string } };
  }>;
  return data
    .filter(c => !c.commit.message.startsWith('Merge '))
    .map(c => {
      const firstLine = c.commit.message.split('\n')[0];
      const { type, scope, body } = parseCommitMessage(firstLine);
      return {
        hash: c.sha.slice(0, 7),
        date: c.commit.author.date.slice(0, 10),
        author: c.commit.author.name,
        message: firstLine,
        type,
        scope,
        body,
      };
    });
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
