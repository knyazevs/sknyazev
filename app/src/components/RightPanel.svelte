<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { BACKEND_URL } from '../config';
  import { computeFingerprint } from '../lib/fingerprint';
  import { marked } from 'marked';
  import CosmicOrb from './CosmicOrb.svelte';
  import DocumentModal from './DocumentModal.svelte';

  function renderMd(text: string, sources?: string[]): string {
    let html = marked.parse(text, { async: false }) as string;
    if (sources?.length) {
      html = html.replace(/\[(\d+)\]/g, (match, num) => {
        const idx = parseInt(num, 10) - 1;
        if (idx < 0 || idx >= sources.length) return match;
        return `<button class="cite-badge" data-source-idx="${idx}">${num}</button>`;
      });
    }
    return html;
  }

  type PanelState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'error';

  const DETAIL_MARKER = '---DETAIL---';

  interface Message {
    question: string;
    answer: string;
    sources: string[];
  }

  function splitAnswer(text: string): { summary: string; detail: string } {
    const idx = text.indexOf(DETAIL_MARKER);
    if (idx < 0) return { summary: text.trim(), detail: '' };
    return {
      summary: text.slice(0, idx).trim(),
      detail: text.slice(idx + DETAIL_MARKER.length).trim(),
    };
  }

  let state: PanelState = $state('idle');
  let inputText = $state('');
  let errorMessage = $state('');
  let streamingText = $state('');
  let history: Message[] = $state([]);
  let modalOpen = $state(false);
  let modalOpenPath = $state<string | null>(null);
  let pendingSources: string[] = [];

  let messagesEl: HTMLElement | undefined = $state();
  let inputEl: HTMLTextAreaElement | undefined = $state();

  let mediaRecorder: MediaRecorder | null = null;
  let audioChunks: BlobPart[] = [];
  let currentAudio: HTMLAudioElement | null = null;
  let ttsEnabled = $state(true);

  let fingerprint = '';

  // ─── View mode ──────────────────────────────────────────────────────────────
  let chatMode = $state(false);
  let expandedDetails: Set<number> = $state(new Set());

  const MODE_KEY = 'chat-mode';

  function openChatMode() {
    chatMode = true;
    sessionStorage.setItem(MODE_KEY, '1');
    // Scroll to bottom after mode switch
    tick().then(scrollMessages);
  }

  function closeChatMode() {
    chatMode = false;
    sessionStorage.setItem(MODE_KEY, '0');
  }

  // ─── Autocomplete ───────────────────────────────────────────────────────────

  let ghostText = $state('');
  let acAbort: AbortController | null = null;
  let acTimer: ReturnType<typeof setTimeout> | null = null;

  function clearAutocomplete() {
    ghostText = '';
    if (acTimer) { clearTimeout(acTimer); acTimer = null; }
    if (acAbort) { acAbort.abort(); acAbort = null; }
  }

  function scheduleAutocomplete(input: string) {
    clearAutocomplete();
    const trimmed = input.trim();
    if (trimmed.length < 3) return;

    // 1) Local prefix match against suggestions + followUps
    const allHints = [...suggestions, ...followUps];
    const localMatch = allHints.find(s =>
      s.toLowerCase().startsWith(trimmed.toLowerCase()) && s.length > trimmed.length
    );
    if (localMatch) {
      ghostText = localMatch.slice(trimmed.length);
      return;
    }

    // 2) Server autocomplete with debounce
    acTimer = setTimeout(async () => {
      acAbort = new AbortController();
      try {
        const res = await fetch(`${BACKEND_URL}/api/autocomplete`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...fpHeaders() },
          body: JSON.stringify({ input: trimmed }),
          signal: acAbort.signal,
        });
        if (!res.ok) return;
        const data = await res.json();
        // Only show if input hasn't changed while we waited
        if (inputText.trim() === trimmed && data.completion) {
          ghostText = data.completion;
        }
      } catch {
        // aborted or network error — ignore
      }
    }, 300);
  }

  function acceptAutocomplete() {
    if (!ghostText) return false;
    inputText = inputText + ghostText;
    ghostText = '';
    return true;
  }

  // ─── Suggestions ────────────────────────────────────────────────────────────

  const DEFAULT_SUGGESTIONS = [
    'Какой стек использует Сергей?',
    'Расскажи про RAG в проекте',
    'Какой опыт в архитектуре?',
    'Почему Kotlin/Ktor?',
  ];

  let suggestions: string[] = $state(DEFAULT_SUGGESTIONS);
  let followUps: string[] = $state([]);

  const HISTORY_KEY = 'chat-history';
  const FOLLOWUPS_KEY = 'chat-followups';

  function persistChat() {
    try {
      sessionStorage.setItem(HISTORY_KEY, JSON.stringify(history));
      sessionStorage.setItem(FOLLOWUPS_KEY, JSON.stringify(followUps));
    } catch { /* quota exceeded — ignore */ }
  }

  onMount(() => {
    try {
      const saved = sessionStorage.getItem(HISTORY_KEY);
      if (saved) {
        const parsed: Message[] = JSON.parse(saved);
        if (parsed.length) history = [...parsed];
      }
      const savedFollowUps = sessionStorage.getItem(FOLLOWUPS_KEY);
      if (savedFollowUps) {
        const parsed: string[] = JSON.parse(savedFollowUps);
        if (parsed.length) followUps = [...parsed];
      }
      chatMode = sessionStorage.getItem(MODE_KEY) === '1';
    } catch { /* corrupted — start fresh */ }

    ttsEnabled = localStorage.getItem('tts') !== 'off';

    (async () => {
      fingerprint = await computeFingerprint();
      try {
        const res = await fetch(`${BACKEND_URL}/api/suggestions`);
        if (res.ok) {
          const data = await res.json();
          if (data.suggestions?.length) suggestions = data.suggestions;
        }
      } catch { /* use defaults */ }
    })();
  });

  function fpHeaders(): Record<string, string> {
    return fingerprint ? { 'X-Fingerprint': fingerprint } : {};
  }

  async function scrollMessages() {
    await tick();
    if (messagesEl) {
      messagesEl.scrollTop = messagesEl.scrollHeight;
    }
  }

  // ─── Chat ─────────────────────────────────────────────────────────────────

  async function submitQuery(question: string) {
    if (!question.trim() || state === 'thinking') return;
    inputText = '';
    errorMessage = '';
    streamingText = '';
    followUps = [];
    clearAutocomplete();
    state = 'thinking';

    const questionText = question.trim();
    if (chatMode) await scrollMessages();

    try {
      const response = await fetch(`${BACKEND_URL}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...fpHeaders() },
        body: JSON.stringify({ question: questionText }),
      });

      if (!response.ok) {
        const body = await response.json().catch(() => null);
        throw new Error(body?.error ?? `Server error: ${response.status}`);
      }
      if (!response.body) {
        throw new Error(`Server error: ${response.status}`);
      }

      state = 'speaking';
      let fullAnswer = '';
      const reader = response.body.getReader();
      const decoder = new TextDecoder();

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        const chunk = decoder.decode(value, { stream: true });
        for (const line of chunk.split('\n')) {
          if (!line.startsWith('data: ')) continue;
          const data = line.slice(6).trim();
          if (data === '[DONE]') break;
          try {
            const parsed = JSON.parse(data);
            if (parsed.token) {
              fullAnswer += parsed.token;
              streamingText = fullAnswer;
              if (chatMode) await scrollMessages();
            }
            if (parsed.sources) {
              pendingSources = parsed.sources;
            }
            if (parsed.suggestions) {
              followUps = parsed.suggestions;
            }
            if (parsed.error) throw new Error(parsed.error);
          } catch {
            // partial JSON — skip
          }
        }
      }

      history = [...history, { question: questionText, answer: fullAnswer, sources: pendingSources }];
      pendingSources = [];
      streamingText = '';
      persistChat();
      if (chatMode) await scrollMessages();

      if (fullAnswer.trim()) {
        const { summary } = splitAnswer(fullAnswer);
        await speakText(summary);
      } else {
        state = 'idle';
      }
    } catch (e: unknown) {
      errorMessage = e instanceof Error ? e.message : 'Что-то пошло не так';
      state = 'error';
    }
  }

  async function handleSubmit() {
    const q = inputText.trim();
    if (!q || state === 'thinking') return;
    await submitQuery(q);
  }

  // ─── TTS ──────────────────────────────────────────────────────────────────

  function toggleTts() {
    ttsEnabled = !ttsEnabled;
    localStorage.setItem('tts', ttsEnabled ? 'on' : 'off');
  }

  async function fetchAudio(text: string): Promise<Blob> {
    const response = await fetch(`${BACKEND_URL}/api/tts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...fpHeaders() },
      body: JSON.stringify({ text }),
    });
    if (!response.ok) throw new Error(`TTS error: ${response.status}`);
    return response.blob();
  }

  function playBlob(blob: Blob): Promise<void> {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(blob);
      currentAudio = new Audio(url);
      currentAudio.onended = () => { URL.revokeObjectURL(url); currentAudio = null; resolve(); };
      currentAudio.onerror = () => { URL.revokeObjectURL(url); currentAudio = null; reject(); };
      currentAudio.play().catch(reject);
    });
  }

  async function speakText(text: string) {
    if (!ttsEnabled) { state = 'idle'; return; }

    const breakIdx = text.indexOf('\n\n');
    const firstPart = breakIdx > 0 ? text.slice(0, breakIdx).trim() : text.trim();
    const restPart = breakIdx > 0 ? text.slice(breakIdx).trim() : '';

    state = 'speaking';
    try {
      const firstBlob = fetchAudio(firstPart);
      const restBlob = restPart ? fetchAudio(restPart) : null;

      await playBlob(await firstBlob);

      if (restBlob) {
        await playBlob(await restBlob);
      }

      state = 'idle';
    } catch {
      state = 'idle';
    }
  }

  // ─── ASR ──────────────────────────────────────────────────────────────────

  async function handleMic() {
    if (state === 'listening') { stopRecording(); return; }
    if (state !== 'idle') return;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      audioChunks = [];
      mediaRecorder = new MediaRecorder(stream);
      mediaRecorder.ondataavailable = (e) => { if (e.data.size > 0) audioChunks.push(e.data); };
      mediaRecorder.onstop = async () => {
        stream.getTracks().forEach(t => t.stop());
        await transcribeAudio(new Blob(audioChunks, { type: 'audio/webm' }));
      };
      mediaRecorder.start();
      state = 'listening';
    } catch {
      errorMessage = 'Нет доступа к микрофону';
      state = 'error';
    }
  }

  function stopRecording() {
    mediaRecorder?.stop();
    mediaRecorder = null;
    state = 'thinking';
  }

  async function transcribeAudio(blob: Blob) {
    try {
      const fd = new FormData();
      fd.append('audio', blob, 'audio.webm');
      const response = await fetch(`${BACKEND_URL}/api/asr`, {
        method: 'POST',
        headers: { ...fpHeaders() },
        body: fd,
      });
      if (!response.ok) throw new Error(`ASR error: ${response.status}`);
      const { text } = await response.json();
      state = 'idle';
      if (text?.trim()) await submitQuery(text.trim());
    } catch {
      errorMessage = 'Ошибка распознавания речи';
      state = 'error';
    }
  }

  // ─── Stop ─────────────────────────────────────────────────────────────────

  function handleStop() {
    currentAudio?.pause();
    currentAudio = null;
    mediaRecorder?.stop();
    mediaRecorder = null;
    state = 'idle';
    streamingText = '';
    errorMessage = '';
  }

  function handleKeydown(e: KeyboardEvent) {
    if (e.key === 'Tab' && ghostText) {
      e.preventDefault();
      acceptAutocomplete();
      return;
    }
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSubmit(); }
    if (e.key === 'Escape' && ghostText) { clearAutocomplete(); }
  }

  function handleInput(e: Event) {
    const el = e.currentTarget as HTMLTextAreaElement;
    autoResize(el);
    scheduleAutocomplete(inputText);
  }

  // ─── Theme ────────────────────────────────────────────────────────────────

  type ThemeMode = 'auto' | 'light' | 'dark';
  let themeMode: ThemeMode = $state('auto');

  onMount(() => {
    themeMode = (localStorage.getItem('theme') as ThemeMode) || 'auto';
  });

  function toggleTheme() {
    const cycle: ThemeMode[] = ['auto', 'light', 'dark'];
    themeMode = cycle[(cycle.indexOf(themeMode) + 1) % 3];
    localStorage.setItem('theme', themeMode);
    const sys = window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', themeMode === 'auto' ? sys : themeMode);
  }

  // ─── Textarea auto-grow ────────────────────────────────────────────────────

  function autoResize(el: HTMLTextAreaElement) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
  }

  // ─── Sources ──────────────────────────────────────────────────────────────

  function isCodeSource(src: string) { return src.startsWith('code/'); }

  function sourceLabel(src: string): string {
    const name = src.split('/').pop() ?? src;
    if (src.startsWith('code/')) return name;
    const base = name.replace(/\.md$/, '');
    const m = base.match(/^(\d+)-(.+)$/);
    if (m) {
      const dir = src.split('/')[0];
      if (dir === 'adr') return `ADR-${parseInt(m[1], 10)}`;
      return m[2].replace(/-/g, ' ').replace(/^\w/, c => c.toUpperCase());
    }
    return base.replace(/-/g, ' ').replace(/^\w/, c => c.toUpperCase());
  }

  function openSourceDoc(src: string) {
    if (isCodeSource(src)) {
      window.location.href = `/code#${src.slice(5)}`;
      return;
    }
    modalOpenPath = src;
    modalOpen = true;
  }

  function handleContentClick(e: MouseEvent) {
    const target = e.target as HTMLElement;

    const badge = target.closest('.cite-badge') as HTMLElement | null;
    if (badge) {
      const idx = parseInt(badge.dataset.sourceIdx ?? '', 10);
      // In chat mode, find which .msg it belongs to
      if (chatMode) {
        const msgEl = badge.closest('.msg');
        if (!msgEl || !messagesEl) return;
        const msgIndex = Array.from(messagesEl.querySelectorAll('.msg')).indexOf(msgEl);
        if (msgIndex < 0 || msgIndex >= history.length) return;
        const src = history[msgIndex].sources?.[idx];
        if (src) openSourceDoc(src);
      } else {
        // Inline mode — last message
        const last = history[history.length - 1];
        const src = last?.sources?.[idx];
        if (src) openSourceDoc(src);
      }
      return;
    }

    const link = target.closest('a') as HTMLAnchorElement | null;
    if (link) {
      const href = link.getAttribute('href') ?? '';
      if (/^https?:\/\//.test(href) && !href.includes(window.location.host)) return;
      const docPath = href
        .replace(/^https?:\/\/[^/]+/, '')
        .replace(/^\//, '');
      if (!docPath) return;
      if (/\.(md|kt|ts|svelte|css|json|toml|yaml|yml|tsx|jsx|js)$/.test(docPath)) {
        e.preventDefault();
        if (docPath.startsWith('code/') || docPath.startsWith('server/') || docPath.startsWith('app/') || docPath.startsWith('scripts/')) {
          const codePath = docPath.startsWith('code/') ? docPath : `code/${docPath}`;
          openSourceDoc(codePath);
        } else {
          openSourceDoc(docPath);
        }
      }
    }
  }

  const stateHints: Partial<Record<PanelState, string>> = {
    listening: 'Слушаю…',
    thinking:  'Думаю…',
  };

  // ─── Derived ──────────────────────────────────────────────────────────────

  let lastMsg = $derived(history.length > 0 ? history[history.length - 1] : null);
  let activeSuggestions = $derived(
    history.length === 0
      ? suggestions
      : followUps.length > 0
        ? followUps
        : suggestions
  );
</script>

<!-- ─── Full-screen orb background ──────────────────────────────────────── -->
<div class="orb-bg" class:chat-mode={chatMode} aria-hidden="true">
  <CosmicOrb {state} />
</div>

<!-- ─── Header (full-width) ──────────────────────────────────────────────── -->
<header class="top-bar">
  <div class="top-bar-inner">
    <div class="identity">
      <span class="name">Sergey Knyazev</span>
      <span class="role">› Technical Lead · Architect<span class="cursor">_</span></span>
    </div>
    <div class="top-actions">
      {#if chatMode}
        <button class="icon-btn" onclick={closeChatMode} aria-label="Свернуть чат" title="Свернуть">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <polyline points="4 14 10 14 10 20"/>
            <polyline points="20 10 14 10 14 4"/>
            <line x1="14" y1="10" x2="21" y2="3"/>
            <line x1="3" y1="21" x2="10" y2="14"/>
          </svg>
        </button>
      {/if}
      <a class="icon-btn" href="/timeline" aria-label="История проекта" title="История">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <circle cx="12" cy="12" r="10"/>
          <polyline points="12 6 12 12 16 14"/>
        </svg>
      </a>
      <a class="icon-btn" href="/code" aria-label="Код проекта" title="Код">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="16 18 22 12 16 6"/>
          <polyline points="8 6 2 12 8 18"/>
        </svg>
      </a>
      <button class="icon-btn" onclick={() => modalOpen = true} aria-label="Документация" title="Документация">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
          <polyline points="14 2 14 8 20 8"/>
          <line x1="16" y1="13" x2="8" y2="13"/>
          <line x1="16" y1="17" x2="8" y2="17"/>
          <line x1="10" y1="9" x2="8" y2="9"/>
        </svg>
      </button>
      <button class="icon-btn" onclick={toggleTheme} aria-label="Тема">
        {#if themeMode === 'light'}
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="12" cy="12" r="5"/>
            <line x1="12" y1="1" x2="12" y2="3"/><line x1="12" y1="21" x2="12" y2="23"/>
            <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/>
            <line x1="1" y1="12" x2="3" y2="12"/><line x1="21" y1="12" x2="23" y2="12"/>
            <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>
          </svg>
        {:else if themeMode === 'dark'}
          <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
            <path d="M21 12.79A9 9 0 1 1 11.21 3a7 7 0 0 0 9.79 9.79z"/>
          </svg>
        {:else}
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <circle cx="12" cy="12" r="5"/>
            <line x1="12" y1="1" x2="12" y2="3"/>
            <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/>
            <line x1="1" y1="12" x2="3" y2="12"/>
            <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/>
            <line x1="12" y1="21" x2="12" y2="23"/>
            <path d="M17 12a5 5 0 0 1-5 5" stroke-dasharray="2 2"/>
          </svg>
        {/if}
      </button>
    </div>
  </div>
</header>

<div class="interface" data-mode={chatMode ? 'chat' : 'inline'}>

  {#if chatMode}
    <!-- ═══════════════════════════════════════════════════════════════════════ -->
    <!-- ─── CHAT MODE ─────────────────────────────────────────────────────── -->
    <!-- ═══════════════════════════════════════════════════════════════════════ -->
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="messages" bind:this={messagesEl} onclick={handleContentClick}>
      {#each history as msg, i}
        {@const parts = splitAnswer(msg.answer)}
        <div class="msg">
          <div class="msg-question">{msg.question}</div>
          <div class="msg-answer md">{@html renderMd(parts.summary, msg.sources)}</div>
          {#if parts.detail}
            {#if expandedDetails.has(i)}
              <div class="msg-detail md">{@html renderMd(parts.detail, msg.sources)}</div>
              <button class="detail-toggle" onclick={() => { expandedDetails.delete(i); expandedDetails = new Set(expandedDetails); }}>
                Свернуть
              </button>
            {:else}
              <button class="detail-toggle" onclick={() => { expandedDetails.add(i); expandedDetails = new Set(expandedDetails); }}>
                Подробнее
              </button>
            {/if}
          {/if}
          {#if msg.sources?.length}
            <div class="msg-sources">
              {#each msg.sources as src, si}
                <button class="source-chip" onclick={() => openSourceDoc(src)}>
                  <span class="source-num">{si + 1}</span>
                  {sourceLabel(src)}
                </button>
              {/each}
            </div>
          {/if}
        </div>
      {/each}
      {#if streamingText}
        {@const streamParts = splitAnswer(streamingText)}
        <div class="msg">
          <div class="msg-answer md streaming">{@html renderMd(streamParts.summary)}<span class="stream-cursor">▊</span></div>
        </div>
      {/if}
      {#if state === 'error'}
        <div class="msg">
          <div class="msg-error">{errorMessage || 'Что-то пошло не так. Попробуйте ещё раз.'}</div>
        </div>
      {/if}
    </div>

  {:else}
    <!-- ═══════════════════════════════════════════════════════════════════════ -->
    <!-- ─── INLINE MODE ───────────────────────────────────────────────────── -->
    <!-- ═══════════════════════════════════════════════════════════════════════ -->
    <!-- svelte-ignore a11y_click_events_have_key_events -->
    <!-- svelte-ignore a11y_no_static_element_interactions -->
    <div class="inline-area" onclick={handleContentClick}>

      {#if stateHints[state]}
        <!-- State hint: thinking / listening -->
        <div class="orb-hint">
          <div class="state-hint">{stateHints[state]}</div>
        </div>
      {:else if streamingText}
        <!-- Streaming answer -->
        {@const streamParts = splitAnswer(streamingText)}
        <div class="inline-answer">
          <div class="msg-answer md streaming">{@html renderMd(streamParts.summary)}<span class="stream-cursor">▊</span></div>
        </div>
      {:else if lastMsg}
        <!-- Last answer (inline) -->
        {@const parts = splitAnswer(lastMsg.answer)}
        <div class="inline-answer">
          <div class="inline-question">{lastMsg.question}</div>
          <div class="msg-answer md">{@html renderMd(parts.summary, lastMsg.sources)}</div>
        </div>
      {:else}
        <!-- Welcome -->
        <div class="orb-hint">
          <div class="state-hint welcome">Интерактивное портфолио Сергея Князева — Technical Lead / Architect. Задайте вопрос об опыте, стеке или проектах, выберите подсказку или используйте микрофон.</div>
        </div>
      {/if}

      {#if state === 'error'}
        <div class="inline-error">
          <div class="msg-error">{errorMessage || 'Что-то пошло не так. Попробуйте ещё раз.'}</div>
        </div>
      {/if}
    </div>
  {/if}

  <!-- ─── Suggestions ──────────────────────────────────────────────────────── -->
  {#if state === 'idle' && activeSuggestions.length > 0}
    <div class="suggestions" role="list">
      {#each activeSuggestions as s}
        <button class="chip" role="listitem" onclick={() => submitQuery(s)}>{s}</button>
      {/each}
    </div>
  {/if}

  <!-- ─── Mode toggle ─────────────────────────────────────────────────────── -->
  {#if !chatMode && history.length > 0 && state === 'idle'}
    <div class="mode-toggle-row">
      <button class="mode-toggle" onclick={openChatMode}>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
        </svg>
        Открыть диалог ({history.length})
      </button>
    </div>
  {/if}

  <!-- ─── Composer ────────────────────────────────────────────────────────── -->
  <div class="composer">
    <div class="composer-inner" class:focused={false}>
      <div class="input-wrap">
        {#if ghostText && inputText}
          <div class="ghost-overlay" aria-hidden="true"><span class="ghost-hidden">{inputText}</span><span class="ghost-completion">{ghostText}</span></div>
        {/if}
        <textarea
          bind:this={inputEl}
          class="composer-input"
          placeholder="Задайте вопрос…"
          rows="1"
          bind:value={inputText}
          onkeydown={handleKeydown}
          oninput={handleInput}
          disabled={state === 'thinking'}
          aria-label="Введите вопрос"
        ></textarea>
      </div>
      <div class="composer-actions">
        {#if state === 'speaking' || state === 'listening'}
          <button class="action-btn stop-btn" onclick={handleStop} aria-label="Остановить">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor">
              <rect x="3" y="3" width="10" height="10" rx="1"/>
            </svg>
          </button>
        {:else}
          <button
            class="action-btn tts-btn"
            class:off={!ttsEnabled}
            onclick={toggleTts}
            aria-label={ttsEnabled ? 'Отключить озвучивание' : 'Включить озвучивание'}
            title={ttsEnabled ? 'Озвучивание вкл.' : 'Озвучивание выкл.'}
          >
            {#if ttsEnabled}
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill="currentColor"/>
                <path d="M15.54 8.46a5 5 0 0 1 0 7.07"/>
                <path d="M19.07 4.93a10 10 0 0 1 0 14.14"/>
              </svg>
            {:else}
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill="currentColor"/>
                <line x1="23" y1="9" x2="17" y2="15"/>
                <line x1="17" y1="9" x2="23" y2="15"/>
              </svg>
            {/if}
          </button>
          <button
            class="action-btn mic-btn"
            class:active={state === 'listening'}
            onclick={handleMic}
            aria-label="Голосовой ввод"
            disabled={state === 'thinking'}
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor">
              <rect x="5" y="1" width="6" height="9" rx="3"/>
              <path d="M2.5 8a5.5 5.5 0 0 0 11 0" stroke="currentColor" stroke-width="1.5" fill="none" stroke-linecap="round"/>
              <line x1="8" y1="13.5" x2="8" y2="15.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
          </button>
          <button
            class="action-btn send-btn"
            onclick={handleSubmit}
            aria-label="Отправить"
            disabled={state === 'thinking' || !inputText.trim()}
          >
            <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor">
              <path d="M2 8L14 2L10 8L14 14L2 8Z"/>
            </svg>
          </button>
        {/if}
      </div>
    </div>
  </div>
</div>

<!-- ─── Document Modal ───────────────────────────────────────────────────── -->
<DocumentModal
  open={modalOpen}
  openPath={modalOpenPath}
  onclose={() => { modalOpen = false; modalOpenPath = null; }}
/>

<style>
  /* ═══════════════════════════════════════════════════════════════════════════ */
  /* ─── Layout ─────────────────────────────────────────────────────────────── */
  /* ═══════════════════════════════════════════════════════════════════════════ */

  /* ─── Top bar (full-width) ─────────────────── */

  .top-bar {
    position: relative;
    z-index: 1;
    width: 100%;
    align-self: stretch;
    flex-shrink: 0;
    padding: 0 24px;
  }

  .top-bar-inner {
    max-width: 760px;
    margin: 0 auto;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 18px 0 12px;
  }

  .interface {
    position: relative;
    z-index: 1;
    width: 100%;
    max-width: 760px;
    margin: 0 auto;
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 0 24px 20px;
    overflow: hidden;
  }

  .identity {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .name {
    font-size: 16px;
    font-weight: 700;
    color: var(--color-text-heading);
    letter-spacing: -0.01em;
    text-shadow: 0 0 40px var(--color-accent-glow);
  }

  .role {
    font-size: 10px;
    color: var(--color-accent);
    font-family: var(--font-mono);
    letter-spacing: 0.06em;
    opacity: 0.75;
    display: flex;
    align-items: center;
    gap: 4px;
  }

  .cursor {
    animation: blink 1.2s step-end infinite;
    color: var(--color-accent);
  }

  @keyframes blink {
    0%, 100% { opacity: 1; }
    50%       { opacity: 0; }
  }

  .top-actions {
    display: flex;
    gap: 8px;
  }

  .icon-btn {
    width: 30px;
    height: 30px;
    border-radius: 7px;
    border: 1px solid var(--color-border);
    background: none;
    color: var(--color-text-dim);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: color var(--transition), border-color var(--transition);
  }

  .icon-btn:hover {
    color: var(--color-accent);
    border-color: var(--color-accent);
  }

  /* ═══════════════════════════════════════════════════════════════════════════ */
  /* ─── INLINE MODE ────────────────────────────────────────────────────────── */
  /* ═══════════════════════════════════════════════════════════════════════════ */

  .inline-area {
    flex: 1;
    display: flex;
    flex-direction: column;
    justify-content: flex-end;
    overflow-y: auto;
    padding: 12px 0;
    gap: 12px;
  }

  .inline-answer {
    display: flex;
    flex-direction: column;
    gap: 8px;
    width: 100%;
    max-width: 100%;
    animation: fadeIn 0.3s ease;
  }

  .inline-question {
    font-size: 12px;
    font-family: var(--font-mono);
    color: var(--color-text-dim);
    letter-spacing: 0.02em;
    padding-bottom: 4px;
  }

  .inline-error {
    width: 100%;
    text-align: center;
    padding-top: 8px;
  }

  /* ─── Orb hint (inline mode, centered) ───── */

  .orb-hint {
    display: flex;
    justify-content: center;
    align-items: center;
    pointer-events: none;
    flex: 1;
  }

  .state-hint {
    font-size: 10px;
    font-family: var(--font-mono);
    color: var(--color-state-hint);
    letter-spacing: 0.08em;
    height: 16px;
    transition: opacity 0.4s ease;
  }

  .state-hint.welcome {
    font-size: 12px;
    line-height: 1.6;
    letter-spacing: 0.02em;
    max-width: 360px;
    text-align: center;
    height: auto;
    color: var(--color-text-dim);
  }

  /* ═══════════════════════════════════════════════════════════════════════════ */
  /* ─── CHAT MODE ──────────────────────────────────────────────────────────── */
  /* ═══════════════════════════════════════════════════════════════════════════ */

  .messages {
    flex: 1;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 12px 0 8px;
    display: flex;
    flex-direction: column;
    gap: 20px;
    scrollbar-gutter: stable;
    mask-image: linear-gradient(to bottom, transparent 0%, black 48px);
  }

  .msg {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .msg-question {
    align-self: flex-end;
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: 12px 12px 3px 12px;
    padding: 9px 14px;
    font-size: 14px;
    color: var(--color-text);
    max-width: 85%;
  }

  /* ═══════════════════════════════════════════════════════════════════════════ */
  /* ─── Answer / Markdown (shared) ─────────────────────────────────────────── */
  /* ═══════════════════════════════════════════════════════════════════════════ */

  .msg-answer {
    align-self: flex-start;
    font-size: 14px;
    line-height: 1.75;
    color: var(--color-text);
    max-width: 100%;
  }

  .msg-answer.md :global(p)          { margin: 0 0 10px; }
  .msg-answer.md :global(p:last-child) { margin-bottom: 0; }
  .msg-answer.md :global(ul),
  .msg-answer.md :global(ol)         { padding-left: 18px; margin: 0 0 10px; }
  .msg-answer.md :global(li)         { margin: 3px 0; }
  .msg-answer.md :global(strong)     { color: var(--color-text-heading); font-weight: 600; }
  .msg-answer.md :global(em)         { font-style: italic; }
  .msg-answer.md :global(h1),
  .msg-answer.md :global(h2),
  .msg-answer.md :global(h3)         { font-size: 14px; font-weight: 600; color: var(--color-text-heading); margin: 14px 0 6px; }
  .msg-answer.md :global(code)       { font-family: var(--font-mono); font-size: 12px; background: var(--color-surface-hover); padding: 1px 5px; border-radius: 3px; color: var(--color-accent); }
  .msg-answer.md :global(pre)        { background: var(--color-surface-hover); border: 1px solid var(--color-border); border-radius: 6px; padding: 10px 12px; overflow-x: auto; margin: 0 0 10px; }
  .msg-answer.md :global(pre code)   { background: none; padding: 0; color: var(--color-text); font-size: 12px; }
  .msg-answer.md :global(blockquote) { border-left: 2px solid var(--color-accent); margin: 0 0 10px; padding: 2px 10px; color: var(--color-text-dim); font-style: italic; }
  .msg-answer.md :global(hr)         { border: none; border-top: 1px solid var(--color-border); margin: 12px 0; }
  .msg-answer.md :global(a)          { color: var(--color-accent); text-decoration: none; }
  .msg-answer.md :global(a:hover)    { text-decoration: underline; }

  .msg-answer.streaming {
    color: var(--color-text-heading);
  }

  .stream-cursor {
    display: inline-block;
    color: var(--color-accent);
    animation: blink 0.8s step-end infinite;
    margin-left: 1px;
  }

  /* ─── Inline citation badges [1] [2] ──── */

  .msg-answer.md :global(.cite-badge),
  .msg-detail.md :global(.cite-badge) {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    border: none;
    background: var(--color-accent);
    color: #fff;
    font-size: 10px;
    font-weight: 700;
    font-family: var(--font-mono);
    cursor: pointer;
    vertical-align: super;
    margin: 0 1px;
    line-height: 1;
    transition: background var(--transition), transform var(--transition);
  }

  .msg-answer.md :global(.cite-badge:hover),
  .msg-detail.md :global(.cite-badge:hover) {
    background: var(--color-send-hover);
    transform: scale(1.15);
  }

  /* ─── Source chips (below answer) ──────── */

  .msg-sources {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 6px;
    padding-top: 8px;
    border-top: 1px solid var(--color-border);
  }

  .source-chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 5px 10px;
    border-radius: 8px;
    border: 1px solid var(--color-border);
    background: var(--color-surface);
    color: var(--color-text);
    font-size: 12px;
    font-family: var(--font-mono);
    cursor: pointer;
    text-decoration: none;
    transition: color var(--transition), border-color var(--transition), background var(--transition);
  }

  .source-chip:hover {
    color: var(--color-accent);
    border-color: var(--color-accent);
    background: var(--color-accent-glow);
  }

  .source-num {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    background: var(--color-accent);
    color: #fff;
    font-size: 10px;
    font-weight: 700;
    flex-shrink: 0;
  }

  /* ─── Detail expand ──────────────────────── */

  .msg-detail {
    align-self: flex-start;
    font-size: 14px;
    line-height: 1.75;
    color: var(--color-text);
    max-width: 100%;
    padding-top: 8px;
    border-top: 1px solid var(--color-border);
    animation: fadeIn 0.3s ease;
  }

  .msg-detail.md :global(p)          { margin: 0 0 10px; }
  .msg-detail.md :global(p:last-child) { margin-bottom: 0; }
  .msg-detail.md :global(ul),
  .msg-detail.md :global(ol)         { padding-left: 18px; margin: 0 0 10px; }
  .msg-detail.md :global(li)         { margin: 3px 0; }
  .msg-detail.md :global(strong)     { color: var(--color-text-heading); font-weight: 600; }
  .msg-detail.md :global(code)       { font-family: var(--font-mono); font-size: 12px; background: var(--color-surface-hover); padding: 1px 5px; border-radius: 3px; color: var(--color-accent); }
  .msg-detail.md :global(pre)        { background: var(--color-surface-hover); border: 1px solid var(--color-border); border-radius: 6px; padding: 10px 12px; overflow-x: auto; margin: 0 0 10px; }
  .msg-detail.md :global(pre code)   { background: none; padding: 0; color: var(--color-text); font-size: 12px; }
  .msg-detail.md :global(a)          { color: var(--color-accent); text-decoration: none; }
  .msg-detail.md :global(a:hover)    { text-decoration: underline; }

  .detail-toggle {
    align-self: flex-start;
    background: none;
    border: none;
    color: var(--color-accent);
    font-size: 12px;
    font-family: var(--font-mono);
    cursor: pointer;
    padding: 2px 0;
    opacity: 0.8;
    transition: opacity var(--transition);
  }

  .detail-toggle:hover {
    opacity: 1;
    text-decoration: underline;
  }

  .msg-error {
    font-size: 13px;
    color: var(--color-error);
    font-family: var(--font-mono);
  }

  /* ═══════════════════════════════════════════════════════════════════════════ */
  /* ─── Orb background ─────────────────────────────────────────────────────── */
  /* ═══════════════════════════════════════════════════════════════════════════ */

  .orb-bg {
    position: fixed;
    inset: 0;
    pointer-events: none;
    z-index: 0;
    transition: opacity 0.5s ease;
  }

  .orb-bg.chat-mode {
    opacity: 0.3;
  }

  /* ═══════════════════════════════════════════════════════════════════════════ */
  /* ─── Suggestions ────────────────────────────────────────────────────────── */
  /* ═══════════════════════════════════════════════════════════════════════════ */

  .suggestions {
    flex-shrink: 0;
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
    padding: 10px 0 8px;
    animation: fadeIn 0.4s ease;
  }

  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(4px); }
    to   { opacity: 1; transform: translateY(0); }
  }

  .chip {
    padding: 6px 14px;
    border-radius: 20px;
    border: 1px solid var(--color-border);
    background: var(--color-surface);
    color: var(--color-text-dim);
    font-size: 12px;
    font-family: var(--font-sans);
    cursor: pointer;
    transition: color var(--transition), border-color var(--transition), background var(--transition);
    white-space: nowrap;
  }

  .chip:hover {
    color: var(--color-text);
    border-color: var(--color-accent);
    background: var(--color-accent-glow);
  }

  /* ─── Mode toggle ────────────────────────── */

  .mode-toggle-row {
    flex-shrink: 0;
    display: flex;
    justify-content: center;
    padding: 4px 0;
  }

  .mode-toggle {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 5px 14px;
    border-radius: 20px;
    border: 1px solid var(--color-border);
    background: none;
    color: var(--color-text-dim);
    font-size: 11px;
    font-family: var(--font-mono);
    cursor: pointer;
    transition: color var(--transition), border-color var(--transition);
  }

  .mode-toggle:hover {
    color: var(--color-accent);
    border-color: var(--color-accent);
  }

  /* ═══════════════════════════════════════════════════════════════════════════ */
  /* ─── Composer ───────────────────────────────────────────────────────────── */
  /* ═══════════════════════════════════════════════════════════════════════════ */

  .composer {
    flex-shrink: 0;
    padding-top: 10px;
  }

  .composer-inner {
    display: flex;
    align-items: flex-end;
    gap: 8px;
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: 14px;
    padding: 10px 12px;
    transition: border-color var(--transition);
  }

  .composer-inner:focus-within {
    border-color: var(--color-accent);
  }

  .input-wrap {
    flex: 1;
    position: relative;
    min-width: 0;
  }

  .composer-input {
    width: 100%;
    background: none;
    border: none;
    outline: none;
    color: var(--color-text);
    font-family: var(--font-sans);
    font-size: 14px;
    line-height: 1.5;
    resize: none;
    max-height: 120px;
    overflow-y: auto;
    position: relative;
    z-index: 1;
  }

  .composer-input::placeholder { color: var(--color-text-dim); }
  .composer-input:disabled { opacity: 0.5; }

  .ghost-overlay {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    font-family: var(--font-sans);
    font-size: 14px;
    line-height: 1.5;
    pointer-events: none;
    white-space: pre-wrap;
    word-break: break-word;
    z-index: 0;
  }

  .ghost-hidden {
    visibility: hidden;
  }

  .ghost-completion {
    color: var(--color-text-dim);
    opacity: 0.5;
  }

  .composer-actions {
    display: flex;
    gap: 6px;
    flex-shrink: 0;
  }

  .action-btn {
    width: 30px;
    height: 30px;
    border-radius: 8px;
    border: none;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: background var(--transition), color var(--transition), opacity var(--transition);
  }

  .action-btn:disabled { opacity: 0.35; cursor: default; }

  .tts-btn {
    background: var(--color-surface-hover);
    color: var(--color-accent);
  }
  .tts-btn:hover { color: var(--color-text); background: var(--color-border); }
  .tts-btn.off { color: var(--color-text-dim); opacity: 0.5; }
  .tts-btn.off:hover { opacity: 0.8; }

  .mic-btn {
    background: var(--color-surface-hover);
    color: var(--color-text-dim);
  }
  .mic-btn:hover:not(:disabled) { color: var(--color-text); background: var(--color-border); }
  .mic-btn.active { background: var(--color-mic-bg); color: var(--color-mic-fg); }

  .send-btn { background: var(--color-accent); color: #fff; }
  .send-btn:hover:not(:disabled) { background: var(--color-send-hover); }

  .stop-btn {
    background: color-mix(in srgb, var(--color-error) 15%, transparent);
    color: var(--color-error);
  }
  .stop-btn:hover { background: rgba(255, 123, 123, 0.25); }
</style>
