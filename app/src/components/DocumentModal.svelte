<script lang="ts">
  import { onMount } from 'svelte';
  import { marked, Renderer } from 'marked';
  import { getDocTree, getDocContent, type DocSection, type DocItem } from '../lib/docs-client.js';

  let { open = false, openPath = null, onclose }: {
    open?: boolean;
    /** Doc path to navigate to immediately (e.g. "experience/001.md"). Skips list view. */
    openPath?: string | null;
    onclose?: () => void;
  } = $props();

  type View = 'list' | 'detail';

  let view: View = $state('list');
  let sections: DocSection[] = $state([]);
  let loading = $state(true);
  let searchQuery = $state('');
  let activeItem: DocItem | null = $state(null);
  let docHtml = $state('');
  let docLoading = $state(false);

  // Кастомный renderer — mermaid-блоки получают класс .mermaid
  const renderer = new Renderer();
  renderer.code = ({ text, lang }: { text: string; lang?: string }) => {
    if (lang === 'mermaid') return `<pre class="mermaid">${text}</pre>`;
    const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;');
    return `<pre><code class="language-${lang ?? ''}">${escaped}</code></pre>`;
  };
  marked.use({ renderer });

  onMount(async () => {
    try {
      const tree = await getDocTree();
      sections = tree.sort((a, b) => {
        if (a.id === 'profile') return -1;
        if (b.id === 'profile') return 1;
        if (a.id === 'blog') return -2;
        return a.id.localeCompare(b.id);
      });
    } finally {
      loading = false;
    }
  });

  // Path to auto-navigate to once sections are loaded
  let pendingPath = $state<string | null>(null);

  // Reset view when modal opens; record pending path if given
  $effect(() => {
    if (open) {
      view = 'list';
      activeItem = null;
      docHtml = '';
      searchQuery = '';
      pendingPath = openPath ?? null;
    }
  });

  // Auto-navigate once sections are available
  $effect(() => {
    if (pendingPath && sections.length > 0 && open) {
      const docPath = `docs/${pendingPath}`;
      const found = allItems.find(item => item.path === docPath);
      if (found) {
        pendingPath = null;
        openDoc(found);
      }
    }
  });

  // Flat list of all items for filtering
  const allItems = $derived(
    sections.flatMap(s => s.items.map(item => ({ ...item, sectionLabel: s.label, sectionId: s.id })))
  );

  let filteredItems = $derived(
    searchQuery.trim().length < 2
      ? allItems
      : allItems.filter(item =>
          item.label.toLowerCase().includes(searchQuery.toLowerCase()) ||
          item.path.toLowerCase().includes(searchQuery.toLowerCase())
        )
  );

  async function openDoc(item: DocItem) {
    activeItem = item;
    docHtml = '';
    docLoading = true;
    view = 'detail';
    try {
      const md = await getDocContent(item.path);
      docHtml = await marked(md) as string;
    } catch {
      docHtml = '<p class="doc-error">Не удалось загрузить документ.</p>';
    } finally {
      docLoading = false;
    }
  }

  function backToList() {
    view = 'list';
    activeItem = null;
    docHtml = '';
  }

  function handleOverlayClick(e: MouseEvent) {
    if (e.target === e.currentTarget) onclose?.();
  }

  function handleKeydown(e: KeyboardEvent) {
    if (e.key === 'Escape') onclose?.();
  }

  function iconForSection(id: string): string {
    const icons: Record<string, string> = {
      adr: '⬡', blog: '✎', profile: '◉', experience: '◈',
      projects: '◆', skills: '◇', diagrams: '⬡',
    };
    return icons[id] ?? '○';
  }

  function excerptFromPath(path: string): string {
    // Show path as breadcrumb-style excerpt
    const parts = path.replace(/\.md$/, '').split('/');
    return parts.join(' › ');
  }
</script>

{#if open}
  <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
  <div
    class="overlay"
    role="dialog"
    aria-modal="true"
    aria-label="Документация"
    onclick={handleOverlayClick}
    onkeydown={handleKeydown}
    tabindex="-1"
  >
    <div class="modal">

      <!-- ─── Header ───────────────────────────────────── -->
      <div class="modal-header">
        {#if view === 'detail' && activeItem}
          <button class="back-btn" onclick={backToList}>
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
              <polyline points="10,3 5,8 10,13"/>
            </svg>
            Назад
          </button>
          <span class="modal-title">{activeItem.label}</span>
        {:else}
          <span class="modal-title">Документация</span>
        {/if}
        <button class="close-btn" onclick={() => onclose?.()} aria-label="Закрыть">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
          </svg>
        </button>
      </div>

      <!-- ─── List view ─────────────────────────────────── -->
      {#if view === 'list'}
        <div class="search-bar">
          <svg class="search-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <input
            class="search-input"
            type="text"
            placeholder="Поиск в документации…"
            bind:value={searchQuery}
            autocomplete="off"
            spellcheck="false"
          />
          {#if searchQuery}
            <button class="search-clear" onclick={() => searchQuery = ''} aria-label="Очистить">
              <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5">
                <line x1="12" y1="4" x2="4" y2="12"/><line x1="4" y1="4" x2="12" y2="12"/>
              </svg>
            </button>
          {/if}
        </div>

        <div class="doc-list">
          {#if loading}
            <div class="list-hint">Загрузка…</div>
          {:else if filteredItems.length === 0}
            <div class="list-hint">Ничего не найдено</div>
          {:else if searchQuery.trim().length >= 2}
            <!-- Search results: flat, Zed-style -->
            {#each filteredItems as item}
              <button class="result-item" onclick={() => openDoc(item)}>
                <div class="result-path">{excerptFromPath(item.path)}</div>
                <div class="result-label">{item.label}</div>
              </button>
            {/each}
          {:else}
            <!-- Default: grouped by section -->
            {#each sections as section}
              {#if section.items.length > 0}
                <div class="section-group">
                  <div class="section-header">
                    <span class="section-icon">{iconForSection(section.id)}</span>
                    <span>{section.label}</span>
                  </div>
                  {#each section.items as item}
                    <button class="result-item indented" onclick={() => openDoc(item)}>
                      <div class="result-label">{item.label}</div>
                      <div class="result-path">{item.path}</div>
                    </button>
                  {/each}
                </div>
              {/if}
            {/each}
          {/if}
        </div>

      <!-- ─── Detail view ───────────────────────────────── -->
      {:else}
        <div class="doc-detail">
          {#if docLoading}
            <div class="list-hint">Загрузка…</div>
          {:else}
            <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
            <article class="doc-content" aria-label={activeItem?.label ?? 'Документ'}>
              {@html docHtml}
            </article>
          {/if}
        </div>
      {/if}

    </div>
  </div>
{/if}

<style>
  /* ─── Overlay ──────────────────────────────────── */

  .overlay {
    position: fixed;
    inset: 0;
    z-index: 500;
    background: var(--color-overlay-bg);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    outline: none;
    animation: fadeOverlay 0.15s ease;
  }

  @keyframes fadeOverlay {
    from { opacity: 0; }
    to   { opacity: 1; }
  }

  /* ─── Modal ────────────────────────────────────── */

  .modal {
    width: 100%;
    max-width: 620px;
    max-height: 80vh;
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: 14px;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    animation: slideUp 0.18s ease;
    box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
  }

  @keyframes slideUp {
    from { opacity: 0; transform: translateY(8px); }
    to   { opacity: 1; transform: translateY(0); }
  }

  /* ─── Header ───────────────────────────────────── */

  .modal-header {
    display: flex;
    align-items: center;
    padding: 14px 16px 12px;
    border-bottom: 1px solid var(--color-border);
    gap: 8px;
    flex-shrink: 0;
  }

  .modal-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text-heading);
    flex: 1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .back-btn {
    display: flex;
    align-items: center;
    gap: 5px;
    background: none;
    border: none;
    color: var(--color-text-dim);
    font-size: 12px;
    font-family: var(--font-mono);
    cursor: pointer;
    padding: 3px 6px 3px 2px;
    border-radius: 5px;
    transition: color var(--transition), background var(--transition);
    flex-shrink: 0;
  }

  .back-btn:hover {
    color: var(--color-accent);
    background: var(--color-accent-glow);
  }

  .close-btn {
    width: 28px;
    height: 28px;
    border-radius: 7px;
    border: 1px solid var(--color-border);
    background: none;
    color: var(--color-text-dim);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    flex-shrink: 0;
    transition: color var(--transition), border-color var(--transition);
  }

  .close-btn:hover {
    color: var(--color-text-heading);
    border-color: var(--color-text-dim);
  }

  /* ─── Search ───────────────────────────────────── */

  .search-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 14px;
    border-bottom: 1px solid var(--color-border);
    flex-shrink: 0;
  }

  .search-icon {
    color: var(--color-text-dim);
    flex-shrink: 0;
  }

  .search-input {
    flex: 1;
    background: none;
    border: none;
    outline: none;
    font-size: 13px;
    font-family: var(--font-mono);
    color: var(--color-text);
    caret-color: var(--color-accent);
  }

  .search-input::placeholder { color: var(--color-text-dim); }

  .search-clear {
    background: none;
    border: none;
    color: var(--color-text-dim);
    cursor: pointer;
    padding: 2px;
    display: flex;
    align-items: center;
    border-radius: 3px;
    transition: color var(--transition);
  }

  .search-clear:hover { color: var(--color-text); }

  /* ─── Doc list ─────────────────────────────────── */

  .doc-list {
    flex: 1;
    overflow-y: auto;
    padding: 6px 0;
  }

  .list-hint {
    padding: 20px;
    text-align: center;
    font-size: 12px;
    color: var(--color-text-dim);
    font-family: var(--font-mono);
  }

  .section-group {
    margin-bottom: 4px;
  }

  .section-header {
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 8px 16px 4px;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.10em;
    text-transform: uppercase;
    color: var(--color-text-dim);
    font-family: var(--font-mono);
    user-select: none;
  }

  .section-icon {
    font-size: 12px;
    opacity: 0.6;
  }

  .result-item {
    display: flex;
    flex-direction: column;
    gap: 2px;
    width: 100%;
    text-align: left;
    padding: 7px 16px;
    background: none;
    border: none;
    cursor: pointer;
    transition: background var(--transition);
    border-left: 2px solid transparent;
  }

  .result-item.indented {
    padding-left: 32px;
  }

  .result-item:hover {
    background: var(--color-surface-hover);
    border-left-color: var(--color-accent);
  }

  .result-label {
    font-size: 13px;
    color: var(--color-text);
    font-family: var(--font-sans);
  }

  .result-path {
    font-size: 11px;
    color: var(--color-text-dim);
    font-family: var(--font-mono);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* ─── Doc detail ───────────────────────────────── */

  .doc-detail {
    flex: 1;
    overflow-y: auto;
    padding: 20px 24px;
  }

  .doc-content {
    font-size: 13px;
    line-height: 1.75;
    color: var(--color-text);
  }

  .doc-content :global(h1) {
    font-size: 16px;
    font-weight: 600;
    color: var(--color-text-heading);
    margin: 0 0 16px;
    padding-bottom: 12px;
    border-bottom: 1px solid var(--color-border);
    line-height: 1.4;
  }

  .doc-content :global(h2) {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text);
    margin: 24px 0 8px;
  }

  .doc-content :global(h3) {
    font-size: 13px;
    font-weight: 600;
    color: var(--color-text);
    margin: 16px 0 6px;
  }

  .doc-content :global(p)          { margin: 0 0 12px; }
  .doc-content :global(ul),
  .doc-content :global(ol)         { padding-left: 20px; margin: 0 0 12px; }
  .doc-content :global(li)         { margin: 3px 0; }
  .doc-content :global(hr)         { border: none; border-top: 1px solid var(--color-border); margin: 20px 0; }
  .doc-content :global(blockquote) { border-left: 2px solid var(--color-accent); margin: 0 0 12px; padding: 2px 12px; color: var(--color-text-dim); font-style: italic; }

  .doc-content :global(code) {
    font-family: var(--font-mono);
    font-size: 12px;
    background: var(--color-surface-hover);
    padding: 1px 5px;
    border-radius: 3px;
    color: var(--color-accent);
  }

  .doc-content :global(pre) {
    background: var(--color-surface-hover);
    border: 1px solid var(--color-border);
    border-radius: 6px;
    padding: 12px;
    overflow-x: auto;
    margin: 0 0 12px;
  }

  .doc-content :global(pre code) {
    background: none;
    padding: 0;
    color: var(--color-text);
    font-size: 12px;
  }

  .doc-content :global(a) {
    color: var(--color-accent);
    text-decoration: none;
  }

  .doc-content :global(a:hover) { text-decoration: underline; }

  .doc-content :global(table) {
    width: 100%;
    border-collapse: collapse;
    margin: 0 0 12px;
    font-size: 12px;
  }

  .doc-content :global(th),
  .doc-content :global(td) {
    padding: 6px 10px;
    border: 1px solid var(--color-border);
    text-align: left;
  }

  .doc-content :global(th) {
    background: var(--color-surface-hover);
    color: var(--color-text);
    font-weight: 600;
  }

  .doc-content :global(.doc-error) {
    color: var(--color-error);
    font-family: var(--font-mono);
    font-size: 12px;
  }

  /* ─── Mobile ───────────────────────────────────── */

  @media (max-width: 480px) {
    .overlay { padding: 0; }
    .modal {
      max-width: 100%;
      max-height: 100vh;
      border-radius: 0;
    }
  }
</style>
