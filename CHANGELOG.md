# Changelog

## [Unreleased]
### M0/M1 — scaffold
- Tycho-иерархия: bom, parent, targets/bundles/features/repositories.
- Target platform: EDT Ruby 2026.1 p2.
- 4 бандла, feature, p2 repository.

### M2 — LLM-слой (core)
- `com.mad.edt.colleague.llm`: `ILLMProvider`, `ChatRequest`/`ChatMessage`/`ChatResult`,
  `ProviderConfig`, `LLMProviderFactory`, `LLMSettings`.
- Клиенты: `OllamaLLMProvider` (`/api/chat`, `/api/tags`, `/api/version`),
  `OpenAiCompatibleLLMProvider` (`/chat/completions`, `/models`).
- Транспорт `java.net.http.HttpClient`, JSON — Gson из target platform.
- Дымовой хук активации в ui-бандле, сетевой smoke по VM-свойству
  `colleague.llm.smoke`.

### M3 — agent loop + skills (core)
- `com.mad.edt.colleague.agent`: `AgentLoop` (ReAct: model -> tool call ->
  результат -> повтор), `IToolExecutor`, `AgentAnswer`.
- `com.mad.edt.colleague.skills`: `SkillLoader` (каталог `<name>/SKILL.md` с
  YAML-шапкой + optional `tools.json`), `SkillDefinition`, `SkillRegistry`
  (рендер секции системного промпта).
- LLM-слой расширен нативным tool calling: `ToolSpec`, `ToolCall`,
  tool-роль сообщений, `ChatResult.getToolCalls`.

### M4 — UI ассистента (ui)
- Вьюшка `ColleagueView`: выбор модели, история диалога, поле вопроса,
  кнопка/Ctrl+Enter, статусная строка; `ChatSession` копит историю и шлёт
  `think:false` для reasoning-шаблонов.
- Категории view в `org.eclipse.ui.views`, `perspectiveExtensions` (стек к
  PropertySheet), команда открытия + `Ctrl+Shift+J`, popup-команда по
  выделению обозревателя, staged-вопросы в открытую вьюшку.
- Автооткрытие вьюшки из `IStartup` при `-Dcolleague.open.view=true`
  (флаг зашит в `1cedt.ini`); end-to-end самопроверка UI по
  `-Dcolleague.ui.selftest=true`.
- Локальный провайдер по умолчанию: модель `qwen3:8b`.

### Контекст проекта — промпты с рабочими артефактами (context)
- `ProjectContext`: описание проекта (имя + раскладка `src/<тип>/<имя>`) и
  ресурса: файл — содержимое в пределах бюджета символов (allowlist текстовых
  суффиксов: `bsl`, `mdo`, `form`, `xml`, ...), папка — список детей.
- `ChatSession.ask(model, context, question)` — контекст уходит отдельным
  system-сообщением `Workspace context:`.
- Popup-команда по выделению обозревателя прикладывает описание выделенного
  объекта вместе с описанием его проекта.
- UI-selftest проверяет ответ с приложенным контекстом (вопрос про
  приложенный фейковый `.bsl` — ответ называет процедуру из контекста).

### M5 — финализация p2
- Чистая установка в новый p2-профиль через
  `org.eclipse.equinox.p2.director`: exit 0, фича + все 4 бандла; для
  транзитивных зависимостей второй `-repository` на локальный p2-пул.
- End-to-end на workspace ERPUH_DEMO: startup-хук, LLM smoke, автооткрытие
  вьюшки, selftest с контекстом — все маркеры в stderr, ошибок в журнале
  workspace нет.

### M6.1–M6.2 — чат-мессенджер (ui)
- `ColleagueView` — оболочка: `SashForm` (панель контактов слева, `CTabFolder`
  чатов справа), по вкладке на модель, история переживает закрытие вкладки
  (реестр `ChatSession` по ключу провайдер+модель).
- `ChatPanel` (`ui.internal`) — чат одной вкладки: история, вопрос/Ctrl+Enter,
  статус, «Новый диалог», attached-context.
- `ContactsPanel` (`ui.internal`) — дерево контактов: узел провайдера со
  статусом ●/○ и его модели-дети; async-обновление, клик открывает вкладку;
  кнопка «+ Подключить провайдер» — заглушка до M6.3.
- Selftest переведён на новый сценарий: загрузка контактов -> вкладка модели
  по умолчанию -> вопрос с контекстом. Проверено живым прогоном.
