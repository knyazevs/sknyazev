<script lang="ts">
  // State machine (ADR-4, ADR-5)
  type PanelState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'error';

  let state: PanelState = $state('idle');
  let caption = $state('');
  let inputText = $state('');
  let errorMessage = $state('');

  import CosmicOrb from './CosmicOrb.svelte';

  // Placeholder: отправка сообщения
  function handleSubmit() {
    if (!inputText.trim()) return;
    const query = inputText.trim();
    inputText = '';
    caption = '';
    state = 'thinking';

    // TODO: вызов backend API (ADR-8)
    setTimeout(() => {
      state = 'speaking';
      caption = `Ответ на: "${query}" — интеграция с LLM в разработке.`;
      setTimeout(() => {
        state = 'idle';
        caption = '';
      }, 3000);
    }, 1200);
  }

  function handleKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  }

  // Mic: placeholder до интеграции Whisper
  function handleMic() {
    if (state === 'listening') {
      state = 'idle';
    } else if (state === 'idle') {
      state = 'listening';
    }
  }

  function handleStop() {
    state = 'idle';
    caption = '';
  }

  const stateLabel: Record<PanelState, string> = {
    idle: '',
    listening: 'Слушаю…',
    thinking: 'Формирую ответ…',
    speaking: '',
    error: '',
  };
</script>

<div class="right-panel-inner" data-state={state}>

  <!-- Сцена: космическая визуальная сущность -->
  <div class="scene">
    <div class="orb-wrap" aria-hidden="true">
      <CosmicOrb {state} />
    </div>
    <div class="interface-hint" class:hidden={state !== 'idle'}>
      голосовой интерфейс · задайте вопрос о системе
    </div>
    {#if state === 'thinking' || state === 'listening'}
      <div class="state-hint">{stateLabel[state]}</div>
    {/if}
  </div>

  <!-- Субтитры -->
  <div class="captions" role="status" aria-live="polite">
    {#if state === 'error'}
      <span class="caption-error">{errorMessage || 'Что-то пошло не так. Попробуйте ещё раз.'}</span>
    {:else}
      {caption}
    {/if}
  </div>

  <!-- Composer: ввод -->
  <div class="composer">
    <div class="composer-inner">
      <textarea
        class="composer-input"
        placeholder="Задайте вопрос…"
        rows="1"
        bind:value={inputText}
        onkeydown={handleKeydown}
        disabled={state === 'thinking'}
        aria-label="Введите вопрос"
      ></textarea>
      <div class="composer-actions">
        {#if state === 'speaking' || state === 'listening'}
          <button class="action-btn stop-btn" onclick={handleStop} aria-label="Остановить">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
              <rect x="3" y="3" width="10" height="10" rx="1"/>
            </svg>
          </button>
        {:else}
          <button
            class="action-btn mic-btn"
            class:active={state === 'listening'}
            onclick={handleMic}
            aria-label="Голосовой ввод"
            disabled={state === 'thinking'}
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
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
            <svg width="16" height="16" viewBox="0 0 16 16" fill="currentColor">
              <path d="M2 8L14 2L10 8L14 14L2 8Z"/>
            </svg>
          </button>
        {/if}
      </div>
    </div>
  </div>
</div>

<style>
  .right-panel-inner {
    display: flex;
    flex-direction: column;
    height: 100vh;
    padding: 24px;
  }

  /* ─── Scene ──────────────────────────────────── */

  .scene {
    flex: 1;
    position: relative;
    overflow: hidden;
  }

  .orb-wrap {
    width: 100%;
    height: 100%;
  }

  .interface-hint {
    position: absolute;
    bottom: 20px;
    left: 50%;
    transform: translateX(-50%);
    font-size: 10px;
    font-family: var(--font-mono);
    color: var(--color-hint);
    letter-spacing: 0.12em;
    white-space: nowrap;
    pointer-events: none;
    transition: opacity 0.6s ease;
  }

  .interface-hint.hidden {
    opacity: 0;
  }

  .state-hint {
    position: absolute;
    bottom: 20px;
    left: 50%;
    transform: translateX(-50%);
    font-size: 11px;
    font-family: var(--font-mono);
    color: var(--color-state-hint);
    letter-spacing: 0.08em;
    pointer-events: none;
    white-space: nowrap;
  }

  /* ─── Captions ───────────────────────────────── */

  .captions {
    min-height: 48px;
    text-align: center;
    font-size: 14px;
    color: var(--color-text);
    line-height: 1.6;
    padding: 0 16px 16px;
  }

  .caption-error {
    color: var(--color-error);
    font-size: 13px;
  }

  /* ─── Composer ───────────────────────────────── */

  .composer {
    padding-top: 12px;
    border-top: 1px solid var(--color-border);
  }

  .composer-inner {
    display: flex;
    align-items: flex-end;
    gap: 8px;
    background: var(--color-surface);
    border: 1px solid var(--color-border);
    border-radius: 12px;
    padding: 10px 12px;
    transition: border-color var(--transition);
  }

  .composer-inner:focus-within {
    border-color: var(--color-accent);
  }

  .composer-input {
    flex: 1;
    background: none;
    border: none;
    outline: none;
    color: var(--color-text);
    font-family: var(--font-sans);
    font-size: 14px;
    line-height: 1.5;
    resize: none;
    max-height: 120px;
  }

  .composer-input::placeholder {
    color: var(--color-text-dim);
  }

  .composer-input:disabled {
    opacity: 0.5;
  }

  .composer-actions {
    display: flex;
    gap: 6px;
    flex-shrink: 0;
  }

  .action-btn {
    width: 32px;
    height: 32px;
    border-radius: 8px;
    border: none;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: background var(--transition), color var(--transition), opacity var(--transition);
  }

  .action-btn:disabled {
    opacity: 0.35;
    cursor: default;
  }

  .mic-btn {
    background: var(--color-surface-hover);
    color: var(--color-text-dim);
  }

  .mic-btn:hover:not(:disabled) {
    color: var(--color-text);
    background: var(--color-border);
  }

  .mic-btn.active {
    background: var(--color-mic-bg);
    color: var(--color-mic-fg);
  }

  .send-btn {
    background: var(--color-accent);
    color: #fff;
  }

  .send-btn:hover:not(:disabled) {
    background: var(--color-send-hover);
  }

  .stop-btn {
    background: color-mix(in srgb, var(--color-error) 15%, transparent);
    color: var(--color-error);
  }

  .stop-btn:hover {
    background: rgba(255, 123, 123, 0.25);
  }

  /* ─── Reduce motion ──────────────────────────── */

  @media (prefers-reduced-motion: reduce) {
    .entity-ring, .entity-core {
      animation: none !important;
    }
  }
</style>
