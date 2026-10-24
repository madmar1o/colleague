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

### M6.3 — подключение провайдера (ui + core)
- `AddProviderDialog`: протокол (локальный/OpenAI-совместимый), имя, URL,
  API-ключ, модель по умолчанию, кнопка «Проверить соединение» со списком
  моделей; кнопка «+ Подключить провайдер» активна.
- `ProviderStore` (core, `llm`): реестр добавленных провайдеров сохраняется
  в instance-preferences (`com.mad.edt.colleague.llm`/`providers.json`,
  Gson); встроенный `local` не сохраняется. Регистрация при создании view.
- Исправление: прикреплённый контекст попадает в историю чата и участвует в
  следующих вопросах до «Нового диалога» (раньше исчезал после первого).

### M6.5 — мобильный режим без перезапуска (ui)
- Переключение «Мобильный/Десктоп» кнопкой в шапке контактов: общие панели
  переносимся (`setParent`) между `SashForm` и `StackLayout`-стэком;
  страница чата с кнопкой «← Контакты».
- Режим хранится в instance-preferences (`com.mad.edt.colleague.ui`/`ui.mobile`),
  восстанавливается при создании view. SWT-специфика: поле `StackLayout.topControl`.

### M7 — режим правки («рубильник») и инструменты проекта (core + context + ui)
- `AgentMode` (core, `agent`): глобальный переключатель режима правки.
  Включение/выключение — два ESC в поле вопроса за <0.8 c; индикатор
  «⚙ режим правки» в шапке чата.
- Инструменты агента (`WorkspaceTools`, ui.internal): `search_code`,
  `list_files`, `read_file` — всегда; `write_file` — только при включённом
  режиме правки и только с модальным подтверждением (diff-size диалог),
  запись через файлы workspace + `refreshLocal`. Пути — только относительные
  от корня workspace.
- `ChatPanel`: при включённом режиме вопрос уходит в `AgentLoop`
  (до 8 шагов, tools из `WorkspaceTools.withWrite`), обмен запоминается
  в истории (`ChatSession.noteExchange`).
- ui-бандл: `Import-Package: com.google.gson` для разбора аргументов tools.

### M8 (база) — поиск контекста проекта (context)
- `CodeSearch`: кэш-индекс исходников (`bsl`, `mdo`, `java`) обходом
  `IResourceVisitor` в фоновом `Job` (без блокировки UI, лимит 60k файлов);
  поиск по запросу — двухпроходный: сначала дешёвый скоринг путей (x5),
  затем чтение содержимого с приоритетом совпавших путей (скоринг строк x3,
  окна-фрагменты ±2 строки). Жёсткие бюджеты: 8 с и 120 мБ на поиск; при
  срезке — пометка о покрытии.
- Автоприкрепление: если контекст не прикреплён вручную, сниппеты поиска
  по тексту вопроса уходят system-сообщением и остаются в истории.
- Самомаркеры: `[colleague] context index ready: N files` и
  `search selftest` (флаг `colleague.search.selftest`) — живая проверка
  на индексе 57 207 файлов ERPUH_DEMO, поиск ~7 с, релевантные фрагменты.

### M7.5 — стиль чата (ui)
- История вкладки — `StyledText` вместо `Text`: сообщения «пузырями» —
  фон сообщения (свои оттенки для вопроса/ответа/ошибки, считаются от
  фонового цвета темы, светлая/тёмная адаптация), жирный префикс автора,
  левые отступы вопроса NBSP, автоскролл к последнему сообщению.
