<script lang="ts">
  import { onMount } from 'svelte';
  import { getCodeTree, getCodeContent, type CodeDir, type CodeFile } from '../lib/docs-client.js';

  type CodeNode = CodeDir | CodeFile;

  function isFile(node: CodeNode): node is CodeFile {
    return 'ext' in node;
  }

  let tree: CodeDir[] = $state([]);
  let loading = $state(true);
  let selectedPath = $state<string | null>(null);
  let fileContent = $state<string | null>(null);
  let fileExt = $state('');
  let fileLoading = $state(false);
  let expanded = $state<Set<string>>(new Set());

  onMount(async () => {
    try {
      tree = await getCodeTree();
      // expand top-level dirs by default
      for (const dir of tree) expanded.add(dir.path);
    } finally {
      loading = false;
    }
    // navigate to hash if present
    const hash = window.location.hash.slice(1);
    if (hash) selectFile(hash);
  });

  function toggleDir(path: string) {
    if (expanded.has(path)) expanded.delete(path);
    else expanded.add(path);
    expanded = new Set(expanded); // trigger reactivity
  }

  async function selectFile(path: string) {
    if (selectedPath === path) return;
    selectedPath = path;
    fileContent = null;
    fileLoading = true;
    window.location.hash = path;
    try {
      const result = await getCodeContent(path);
      fileContent = result.content;
      fileExt = result.ext;
    } catch {
      fileContent = '// Не удалось загрузить файл';
    } finally {
      fileLoading = false;
    }
  }

  function formatSize(bytes: number): string {
    if (bytes < 1024) return `${bytes}b`;
    return `${(bytes / 1024).toFixed(1)}k`;
  }

  function lineCount(content: string): number {
    return content.split('\n').length;
  }
</script>

<div class="browser">
  <!-- ─── Sidebar ─────────────────────────────────────────────── -->
  <aside class="sidebar">
    <div class="sidebar-header">
      <a href="/" class="back-link">
        <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <polyline points="10,3 5,8 10,13"/>
        </svg>
        Назад
      </a>
      <span class="sidebar-title">Код</span>
    </div>

    <div class="tree">
      {#if loading}
        <div class="tree-hint">Загрузка…</div>
      {:else if tree.length === 0}
        <div class="tree-hint">Нет файлов</div>
      {:else}
        {#each tree as dir}
          {@render nodeTree(dir, 0)}
        {/each}
      {/if}
    </div>
  </aside>

  <!-- ─── Content ──────────────────────────────────────────────── -->
  <main class="content">
    {#if !selectedPath}
      <div class="empty-state">
        <div class="empty-icon">{"{}"}</div>
        <div class="empty-text">Выберите файл в дереве слева</div>
      </div>
    {:else if fileLoading}
      <div class="empty-state">
        <div class="empty-text">Загрузка…</div>
      </div>
    {:else if fileContent !== null}
      <div class="file-header">
        <span class="file-path">{selectedPath}</span>
        <span class="file-meta">{lineCount(fileContent)} строк · {fileExt}</span>
      </div>
      <pre class="code-view"><code>{fileContent}</code></pre>
    {/if}
  </main>
</div>

{#snippet nodeTree(node: CodeNode, depth: number)}
  {#if isFile(node)}
    <button
      class="tree-file"
      class:active={selectedPath === node.path}
      style="padding-left: {16 + depth * 14}px"
      onclick={() => selectFile(node.path)}
    >
      <span class="file-icon">{fileIcon(node.ext)}</span>
      <span class="node-name">{node.name}</span>
      <span class="node-size">{formatSize(node.size)}</span>
    </button>
  {:else}
    <button
      class="tree-dir"
      style="padding-left: {16 + depth * 14}px"
      onclick={() => toggleDir(node.path)}
    >
      <span class="dir-chevron" class:open={expanded.has(node.path)}>›</span>
      <span class="node-name">{node.name}</span>
    </button>
    {#if expanded.has(node.path)}
      {#each node.children as child}
        {@render nodeTree(child, depth + 1)}
      {/each}
    {/if}
  {/if}
{/snippet}

<script module lang="ts">
  function fileIcon(ext: string): string {
    const icons: Record<string, string> = {
      kt: 'K', kts: 'K', ts: 'T', js: 'J', mjs: 'J',
      svelte: 'S', toml: '⚙', yml: '⚙', yaml: '⚙',
      json: '{}', md: '≡', conf: '⚙', env: '$',
    };
    return icons[ext] ?? '·';
  }
</script>

<style>
  .browser {
    display: flex;
    height: 100vh;
    overflow: hidden;
    background: var(--color-bg);
  }

  /* ─── Sidebar ─────────────────────────────── */

  .sidebar {
    width: 260px;
    flex-shrink: 0;
    border-right: 1px solid var(--color-border);
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .sidebar-header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 16px 14px 12px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .back-link {
    display: flex;
    align-items: center;
    gap: 4px;
    color: var(--color-text-dim);
    font-size: 11px;
    font-family: var(--font-mono);
    text-decoration: none;
    transition: color var(--transition);
  }

  .back-link:hover { color: var(--color-accent); }

  .sidebar-title {
    font-size: 12px;
    font-weight: 600;
    color: var(--color-text-heading);
    font-family: var(--font-mono);
    letter-spacing: 0.05em;
  }

  .tree {
    flex: 1;
    overflow-y: auto;
    padding: 6px 0;
  }

  .tree-hint {
    padding: 16px;
    font-size: 12px;
    color: var(--color-text-dim);
    font-family: var(--font-mono);
    text-align: center;
  }

  .tree-dir,
  .tree-file {
    display: flex;
    align-items: center;
    gap: 6px;
    width: 100%;
    background: none;
    border: none;
    text-align: left;
    font-family: var(--font-mono);
    font-size: 12px;
    color: var(--color-text-dim);
    padding: 3px 12px 3px 16px;
    cursor: pointer;
    white-space: nowrap;
    overflow: hidden;
    transition: background var(--transition), color var(--transition);
    border-left: 2px solid transparent;
  }

  .tree-dir:hover { color: var(--color-text); background: var(--color-surface-hover); }
  .tree-file:hover { color: var(--color-text); background: var(--color-surface-hover); }
  .tree-file.active {
    color: var(--color-accent);
    background: var(--color-accent-glow);
    border-left-color: var(--color-accent);
  }

  .dir-chevron {
    font-size: 11px;
    opacity: 0.5;
    transition: transform 0.15s ease;
    display: inline-block;
    transform: rotate(0deg);
    width: 10px;
  }

  .dir-chevron.open { transform: rotate(90deg); }

  .file-icon {
    font-size: 9px;
    font-weight: 700;
    width: 14px;
    text-align: center;
    opacity: 0.5;
    flex-shrink: 0;
  }

  .node-name {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .node-size {
    font-size: 10px;
    opacity: 0.35;
    flex-shrink: 0;
  }

  /* ─── Content ────────────────────────────── */

  .content {
    flex: 1;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }

  .empty-state {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    color: var(--color-text-dim);
  }

  .empty-icon {
    font-size: 28px;
    font-family: var(--font-mono);
    opacity: 0.3;
  }

  .empty-text {
    font-size: 13px;
    font-family: var(--font-mono);
    opacity: 0.5;
  }

  .file-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 10px 20px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
    gap: 12px;
  }

  .file-path {
    font-family: var(--font-mono);
    font-size: 12px;
    color: var(--color-text-dim);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .file-meta {
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--color-text-dim);
    opacity: 0.5;
    flex-shrink: 0;
  }

  .code-view {
    flex: 1;
    overflow: auto;
    margin: 0;
    padding: 16px 20px;
    font-family: var(--font-mono);
    font-size: 12.5px;
    line-height: 1.65;
    color: var(--color-text);
    background: none;
    border: none;
    white-space: pre;
    tab-size: 2;
  }

  /* ─── Mobile ─────────────────────────────── */

  @media (max-width: 640px) {
    .sidebar { width: 200px; }
  }
</style>
