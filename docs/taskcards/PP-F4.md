# PP-F4 — Субагенты (delegate) с вложенным read-only циклом

Веха: M24 | Ветка: `feature/pp-f4-subagents` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-F4`
Зависимости: PP-F3 (профили)

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-F4) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M24.

## Что сделать (пошагово)
1) Красный тест SubagentToolTest (scripted-модель): foregroundSubagentRunsNestedLoop() — вложенный цикл делает tool_call и возвращает финал; depthLimitRefusesNesting() — вложенный субагент не может вызвать subagent (ToolFailure про глубину); resultsKeepCallOrder() — переиспользовать barrier-тесты F28.
2) core пакет com.mad.edt.colleague.agent.subagent: SubagentTool.java (декларация subagent: agent, description, prompt; execute запускает вложенный AgentLoop со своими maxSteps=4 и read-only инструментами родителя), SubagentRunner.java (Eclipse Job colleague-subagent-*, общий GeneratorGate), SubagentRegistry.java (live-сессии для продолжения, потолок 8 записей, eviction старых). Глубина вложенности — константа 1.
3) UI: событие kind:subagent на страницу (карточка «субагент: описание -> готово»).

## Критерии приёмки
тесты зелёные; в e2e-трейсе виден вызов subagent и его финал; вложенный subagent отказывает по глубине.

## Сообщение коммита
`feat: delegate subagents with a nested read-only loop`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Hands a bounded task to a nested agent loop that runs on its own Eclipse
 * job with its own step budget and the parent's read-only tools. The nested
 * loop never offers write tools, so a delegate cannot change files.
 */
public final class SubagentTool {
    /** @return tool declaration named subagent */
    public ToolSpec spec();
    /**
     * Runs one delegate turn.
     *
     * @param theAgent profile name, null for the default profile
     * @param thePrompt task text for the delegate
     * @return final answer of the delegate, or its failure description
     */
    public String execute(final String theAgent, final String thePrompt);
}
```

## Где искать информацию
- PARITY-PLAN.md -> F4; RESEARCH-extensibility-mechanics.md §3; barrier-тесты F28 — AgentLoop.java:256-292
- Конвенции репозитория: AGENTS.md в корне этого репозитория.
- Карточки остальных задач плана: docs/taskcards/ (в milestone-ветках вех).

## Принципы проекта (нарушение = задача не засчитана)
1. TDD red-first: сначала красный тест, потом реализация, потом зелёный. Тестовые фрагменты:
   com.mad.edt.colleague.tests / com.mad.edt.colleague.context.tests (JUnit 5).
2. Сборка: cd "colleague"; $env:JAVA_HOME="C:\Program Files\Zulu\zulu-17"; .\mvnw.cmd -B clean verify.
   Каждый коммит — с зелёным clean verify.
3. SOLID: отдельный пакет и свои интерфейсы на подсистему; слои UI -> agent -> llm/skills/...;
   core-пакеты не импортируют org.eclipse.* кроме Platform/Preferences/IProject/Job.
4. DRY: frontmatter-парсер и reload-реестр — один общий механизм (PP-F0) для скилов/команд/профилей.
5. ООП: инструменты агента реализуют существующий контракт ToolSpec; не вводить «ещё один executable entry».
6. Javadoc на КАЖДУЮ новую функцию: класс — абзац «зачем»; метод — описание + @param/@return/@throws;
   Javadoc на английском, параметры вида theXxx.
7. Коммиты: feat|fix|test|refactor|docs|chore: <описание> (тип perf: хук не принимает).
8. В репозиторий не заносить названия чужих продуктов и их внутренность (colleague/AGENTS.md);
   «эталон» = поведение из референсных исходников ВНЕ репозитория.
9. Не менять Bundle-Version, targets/*.target, версии Tycho/EDT «за компанию» с задачей.

## Итог реализации (2026-10-29)
Выполнено. Коммиты: `ad78542` (test, красный: SubagentToolTest + SubagentRegistryTest) →
`1e26ef6` (feat: delegate subagents with a nested read-only loop — core-пакет) → `c4278fc`
(feat: карточка «субагент … — готово» на странице + мост kind:subagent) → `eec9dfd`
(test: e2e-сценарий делегирования реальной моделью). done_commit=`1e26ef6` (ядро; UI-обвязка —
`c4278fc`, e2e-доказательство — `eec9dfd`).

- Пакет `com.mad.edt.colleague.agent.subagent` (core, добавлен в Export-Package):
  `SubagentTool` (инструмент `subagent`, аргументы agent/description/prompt; вложенный
  `AgentLoop` с `SUBAGENT_MAX_STEPS=4`, глубина `MAX_DEPTH=1`, live-сессии),
  `SubagentRunner` (Eclipse Job `colleague-subagent-<agent>`, общий `GeneratorGate.GLOBAL`,
  потолок ожидания хода `TURN_WAIT_MS=240 c`, Job разрешён правилом 3),
  `SubagentRegistry` (live-сессии делегатов, `MAX_SESSIONS=8`, eviction старейшего,
  повторное имя заменяет запись на месте).
- Вложенный цикл продолжает историю делегата: у `AgentLoop` появился вход `run(List<ChatMessage>)`
  (существующий `run(system, user)` делегирует ему, поведение прежнее).
- Read-only граница: делегат получает инструменты родителя, у которых `isParallelSafe()` и имя не
  writer (по `ToolPolicy.isWriteTool`), плюс разрешён профилем; вложенный экземпляр `SubagentTool`
  добавляется всегда — модель получает явный отказ по глубине вместо unknown-tool.
- Профили F3: имя делегата ищется среди профилей с `mode != PRIMARY`; model/max-steps/тело
  профиля применяются к вложенному циклу.
- UI: `ChatPanel` предлагает `subagent` в edit-mode (если профиль не вычеркнул имя), реестр
  live-делегатов живёт на панель между ходами; `SubagentTool.DelegateListener` → `pushNow
  ("subagent", {agent, description, result})`; в `chat.html` `addSubagent` — чип-строка
  «субагент <имя>: <описание> — готово/сбой», результат в `title`.
- Отклонения: (1) сигнатура Javadoc-каркаса (`spec()`, `execute(agent, prompt)`) приведена к
  контракту `IToolExecutor`/`ToolSpec` (правило 5): `getName/getSpec/execute(json)`, публичный
  `run(agent, description, prompt)`; (2) критерий read-only ужестолнён против буквального
  «read-only инструменты родителя»: планировщик и память уроков меняют состояние, поэтому
  фильтруются по `isParallelSafe()`, а не только по имени writer; (3) добавлен четвёртый тест
  `liveSessionContinuesTheSameDelegate` (реестр live-сессий — часть карточки, требовал фиксации);
  (4) «в e2e-трейсе виден вызов subagent» реализовано вторым gated-сценарием
  `realModelDelegatesToASubagent` (`COLLEAGUE_E2E=1`): в трейс Лога попадает `tool subagent ... ->
  8 chars`, ассерт проверяет, что ответ делегата вернулся результат-сообщением, `edit_file`
  в трейсе не появился, файл не изменён.
