import fs from 'node:fs';
import path from 'node:path';
import type { Plugin, Connect } from 'vite';
import { EXCLUDE_FILES, DIR_LABELS, FILE_LABELS } from '../config.js';

// Корень репозитория относительно этого файла:
// app/src/lib/ → ../../.. → repo root
const REPO_ROOT = path.resolve(import.meta.dirname, '../../..');
const DOCS_ROOT = path.join(REPO_ROOT, 'docs');

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

function buildTree(): DocSection[] {
  const sections: DocSection[] = [];

  // Поддиректории docs/
  if (!fs.existsSync(DOCS_ROOT)) return sections;

  const entries = fs.readdirSync(DOCS_ROOT, { withFileTypes: true });
  const dirs = entries.filter(e => e.isDirectory()).sort((a, b) => a.name.localeCompare(b.name));

  for (const dir of dirs) {
    const dirPath = path.join(DOCS_ROOT, dir.name);
    const allFiles = fs.readdirSync(dirPath).filter(f => f.endsWith('.md')).sort();
    const hasReadme = allFiles.includes('README.md');
    const files = allFiles.filter(f => f !== 'README.md' && !EXCLUDE_FILES.includes(f));

    if (files.length === 0 && !hasReadme) continue;

    sections.push({
      id: dir.name,
      label: DIR_LABELS[dir.name] ?? dir.name,
      items: files.map(f => ({
        path: `docs/${dir.name}/${f}`,
        label: labelFromFilename(f, dir.name),
      })),
      readmePath: hasReadme ? `docs/${dir.name}/README.md` : undefined,
    });
  }

  return sections;
}

function getContent(filePath: string): string | null {
  const abs = path.join(REPO_ROOT, filePath);
  if (!fs.existsSync(abs)) return null;
  return fs.readFileSync(abs, 'utf-8');
}

function json(res: Connect.ServerResponse, data: unknown, status = 200) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.end(JSON.stringify(data));
}

export function docsPlugin(): Plugin {
  return {
    name: 'docs-api',
    configureServer(server) {
      server.middlewares.use('/api/docs', (req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://localhost');

        if (url.pathname === '/tree') {
          return json(res, { sections: buildTree() });
        }

        if (url.pathname === '/content') {
          const filePath = url.searchParams.get('path');
          if (!filePath) return json(res, { error: 'path required' }, 400);
          const content = getContent(filePath);
          if (content === null) return json(res, { error: 'not found' }, 404);
          return json(res, { content });
        }

        next();
      });
    },
  };
}
