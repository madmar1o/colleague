# INDEX задач плана PARITY (PP-*)

Сгенерировано из борда Qdrant `colleague_tasks` 2026-10-28. Истинные статусы — в Qdrant (см. команду ниже); этот файл — маршрутизация.
Статусы обновлены 2026-11-05 по борду: все PP-P*/PP-F* — `done`; PP-E4 — `in_progress` (ждёт живой приёмки).


## Как агенту начать задачу (протокол)
1. Выбрать первую незакрытую задачу по порядку вех M21->M27 с учётом зависимостей (колонка Deps).
2. Проверить/обновить статус в борде REST-командой (MCP не обязателен):
   ```powershell
   Invoke-RestMethod -Uri "http://127.0.0.1:6333/collections/colleague_tasks/points/scroll" `
     -Method Post -ContentType "application/json" `
     -Body '{"limit":30,"with_payload":true,"filter":{"must":[{"key":"id","match":{"value":"<ID>"}}]}}'
   ```
   Статусы: pending -> in_progress (в начале) -> done + done_commit (после приёмки;
   запись через POST /collections/colleague_tasks/points/payload, PUT точек не использовать).
3. `git checkout <Branch>`; прочитать `docs/taskcards/<ID>.md` и `git notes show` — в карточке
   лежат все шаги, критерии приёмки, Javadoc-каркас, ссылки на обоснования.
4. Работать по карточке: красный тест -> реализация -> зелёный; шаг = коммит; коммиты только
   допустимыми типами (feat|fix|test|refactor|docs|chore); сборка зелёная перед каждым коммитом.
5. После приёмки: done+done_commit в Qdrant, мердж ветки в `milestone/<веха>`, следующая задача.

## Вехи и задачи

### M21

| Задача | Статус | Зависимости | Git-ветка | Карточка | Точка Qdrant |
|---|---|---|---|---|---|
| PP-P1 Push-доставка событий на чат-страницу вместо ожидания поллинга | done | нет (первая задача плана) | `feature/pp-p1-push-events` | `docs/taskcards/PP-P1.md` | `10000000-0000-4000-8000-000000000001` |
| PP-P2 Укрупнить пакеты стрима (меньше задач UI-очереди) | done | нет; можно после PP-P1 | `feature/pp-p2-stream-batches` | `docs/taskcards/PP-P2.md` | `10000000-0000-4000-8000-000000000002` |
| PP-P3 Ленивое создание браузера и отложенная индексация на старте | done | нет | `feature/pp-p3-lazy-startup` | `docs/taskcards/PP-P3.md` | `10000000-0000-4000-8000-000000000003` |
| PP-P4 Явный сигнал обрезки контекста + адаптивный num_ctx | done | нет | `feature/pp-p4-truncation-signal` | `docs/taskcards/PP-P4.md` | `10000000-0000-4000-8000-000000000004` |

### M22

| Задача | Статус | Зависимости | Git-ветка | Карточка | Точка Qdrant |
|---|---|---|---|---|---|
| PP-P5 Пустой финальный раунд — не платить дважды | done | нет | `feature/pp-p5-repair-round` | `docs/taskcards/PP-P5.md` | `10000000-0000-4000-8000-000000000005` |
| PP-P6 Развести чат-генератор и эмбеддинг-рантайм | done | нет | `feature/pp-p6-generator-gate` | `docs/taskcards/PP-P6.md` | `10000000-0000-4000-8000-000000000006` |
| PP-P7 Perf-бенчмарк как часть e2e-харнеса | done | PP-P5 (статистика раундов), PP-P6 | `feature/pp-p7-e2e-benchmark` | `docs/taskcards/PP-P7.md` | `10000000-0000-4000-8000-000000000007` |

### M23

| Задача | Статус | Зависимости | Git-ветка | Карточка | Точка Qdrant |
|---|---|---|---|---|---|
| PP-F0 Общий frontmatter-загрузчик и reload-реестр (DRY-фундамент) | done | нет; начинать после слияния PP-P1..P7 в main (порядок вех) | `feature/pp-f0-frontmatter-parser` | `docs/taskcards/PP-F0.md` | `10000000-0000-4000-8000-000000000008` |
| PP-F1 Скилы: витрина + ленивая загрузка (экономия контекста) | done | PP-F0 | `feature/pp-f1-skill-showcase` | `docs/taskcards/PP-F1.md` | `10000000-0000-4000-8000-000000000009` |
| PP-F2 Slash-команды пользователя из markdown-файлов проекта | done | PP-F0 | `feature/pp-f2-slash-commands` | `docs/taskcards/PP-F2.md` | `10000000-0000-4000-8000-000000000010` |
| PP-F9 Каталог команд/скилов/профилей на чат-страницу | done | PP-P1 (pushEvents), PP-F2 | `feature/pp-f9-catalog-events` | `docs/taskcards/PP-F9.md` | `10000000-0000-4000-8000-000000000011` |

### M24

| Задача | Статус | Зависимости | Git-ветка | Карточка | Точка Qdrant |
|---|---|---|---|---|---|
| PP-F3 Профили агентов (per-agent tool policy) | done | PP-F0 | `feature/pp-f3-agent-profiles` | `docs/taskcards/PP-F3.md` | `10000000-0000-4000-8000-000000000012` |
| PP-F4 Субагенты (delegate) с вложенным read-only циклом | done | PP-F3 (профили) | `feature/pp-f4-subagents` | `docs/taskcards/PP-F4.md` | `10000000-0000-4000-8000-000000000013` |
| PP-F10 Убрать мёртвые инструменты из advertised-каталога | done | PP-F3 (ToolPolicy) | `feature/pp-f10-prune-tools` | `docs/taskcards/PP-F10.md` | `10000000-0000-4000-8000-000000000014` |

### M25

| Задача | Статус | Зависимости | Git-ветка | Карточка | Точка Qdrant |
|---|---|---|---|---|---|
| PP-F5 MCP-клиент (stdio + HTTP) и адаптер инструментов | done | PP-F0 | `feature/pp-f5-mcp-client` | `docs/taskcards/PP-F5.md` | `10000000-0000-4000-8000-000000000015` |
| PP-F6 Хук-реестр (plugin API) + OSGi extension point для инструментов | done | PP-F0 | `feature/pp-f6-hook-registry` | `docs/taskcards/PP-F6.md` | `10000000-0000-4000-8000-000000000016` |

### M26

| Задача | Статус | Зависимости | Git-ветка | Карточка | Точка Qdrant |
|---|---|---|---|---|---|
| PP-F7 Долговечные сессии чата (durable store) | done | нет | `feature/pp-f7-conversation-store` | `docs/taskcards/PP-F7.md` | `10000000-0000-4000-8000-000000000017` |
| PP-F8 Компакция истории по порогу токенов | done | PP-P4 (оценка done_reason уже читается) | `feature/pp-f8-compact-by-tokens` | `docs/taskcards/PP-F8.md` | `10000000-0000-4000-8000-000000000018` |

### M27

| Задача | Статус | Зависимости | Git-ветка | Карточка | Точка Qdrant |
|---|---|---|---|---|---|
| PP-E1 Контекст активного редактора для автоконтекста | done | нет | `feature/pp-e1-active-editor` | `docs/taskcards/PP-E1.md` | `10000000-0000-4000-8000-000000000019` |
| PP-E2 Explain-selection / generate-method из меню редактора | done | PP-F3 (профиль read-only) | `feature/pp-e2-editor-actions` | `docs/taskcards/PP-E2.md` | `10000000-0000-4000-8000-000000000020` |
| PP-E3 Инструменты агента find_metadata / read_metadata | done | нет | `feature/pp-e3-metadata-tools` | `docs/taskcards/PP-E3.md` | `10000000-0000-4000-8000-000000000021` |
| PP-E4 Клик путь:строка в ответе чата открывает редактор | in_progress | PP-P1 (мост уже умеет push), PP-E1 (не обязателен) | `feature/pp-e4-open-in-editor` | `docs/taskcards/PP-E4.md` | `10000000-0000-4000-8000-000000000022` |


Примечание: карточки задач лежат в соответствующих feature-ветках (коммит `docs: task card ...`)
и появятся здесь после мерджа вех. Статусы в таблице — снапшот на дату генерации.

---

## Параллельная пачка AG-* (UX агента в чате) — план 2026-10-30, статус: выполнена 2026-11-04

Карточки: `docs/taskcards/AGENT-UX-PLAN.md` (общие факты и порядок), `AG-4-step-budget.md`,
`AG-1-tool-trace.md`, `AG-2-modes.md`, `AG-3-agent-contacts.md`.
Борд: Qdrant `oc_plans` (project=Colleague); пачка закрыта, работа влита в
интеграционную линию `feature/pp-e4-open-in-editor` (см. root AGENTS.md, «Модель ветвления»).
