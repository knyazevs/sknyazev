#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# knyazevs — скрипт деплоя сервера на VPS.
# TLS и reverse-proxy держит Nginx Proxy Manager на хосте
# (api.sknyazev.pro → 127.0.0.1:8091). Compose только поднимает Ktor.
# Git pull выполняется ДО запуска этого скрипта (в GitHub Actions workflow).
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

DEPLOY_PATH="${DEPLOY_PATH:-/opt/knyazevs}"
cd "$DEPLOY_PATH"

# ── Рабочие директории ────────────────────────────────────────────────────────
echo "==> Создание рабочих директорий"
mkdir -p data

# ── Генерация .env ────────────────────────────────────────────────────────────
echo "==> Генерация .env"
cat > .env << EOF
# Сгенерировано автоматически при деплое $(date -u +"%Y-%m-%dT%H:%M:%SZ")

OPENROUTER_API_KEY=${OPENROUTER_API_KEY}
OPENAI_API_KEY=${OPENAI_API_KEY}
SESSION_SECRET=${SESSION_SECRET}
LLM_BASE_URL=${LLM_BASE_URL:-https://openrouter.ai/api/v1}
LLM_MODEL=${LLM_MODEL:-anthropic/claude-opus-4-7}
CLASSIFIER_MODEL=${CLASSIFIER_MODEL:-openai/gpt-4o-mini}
EMBEDDING_BASE_URL=${EMBEDDING_BASE_URL:-https://api.openai.com/v1}
EMBEDDING_MODEL=${EMBEDDING_MODEL:-text-embedding-3-small}
CORS_ALLOWED_ORIGIN=${CORS_ALLOWED_ORIGIN:-https://knyazevs.github.io}
EOF

chmod 600 .env

# ── Сборка и запуск ──────────────────────────────────────────────────────────
echo "==> docker compose build & up"
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d --remove-orphans

# ── Очистка старых образов ────────────────────────────────────────────────────
echo "==> Очистка старых образов"
docker image prune -f

# ── Проверка здоровья ────────────────────────────────────────────────────────
# Бьём прямо в Ktor через host-порт 8091 — до NPM, чтобы health-check
# не зависел от конфигурации proxy/TLS.
echo "==> Ожидание запуска сервера..."
HTTP="000"
for i in $(seq 1 30); do
  sleep 3
  HTTP=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
    -X POST http://127.0.0.1:8091/api/session || true)
  echo "  попытка $i/30 — статус: $HTTP"
  # 200/400/401/403 = сервер отвечает; 000/5xx = ещё не поднялся.
  [[ "$HTTP" =~ ^(200|400|401|403)$ ]] && break
done

if [[ "$HTTP" =~ ^(200|400|401|403)$ ]]; then
  echo "✅ Деплой успешен (статус $HTTP)"
else
  echo "❌ Сервер не ответил после 90 секунд (последний статус: $HTTP)"
  docker compose -f docker-compose.prod.yml logs --tail=150 ktor
  exit 1
fi
