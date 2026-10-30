# PP-F3 — Профили агентов (per-agent tool policy)

Веха: M24 | Ветка: `feature/pp-f3-agent-profiles` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-F3`
Зависимости: PP-F0

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-F3) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M24.

## Что сделать (пошагово)
1) Красные тесты: AgentProfileLoaderTest (parsesToolListAndSteps()), ProfileToolPolicyTest — модель с профилем «только read-only» не получает write_file в advertised tools.
2) core пакет com.mad.edt.colleague.agents: AgentProfile.java (имя, description, system, modelName, maxSteps, mode primary|subagent|all, toolNames Set, hidden), AgentProfileLoader.java (тот же Frontmatter, каталог <workspace>/.colleague/agents/*.md), ToolPolicy.java (фильтр advertised-списка; read-only инструменты всегда можно; write — только в edit-режиме; профиль сужает, никогда не расширяет write-доступ).
3) Врезка в ChatPanel там же, где toolSupplier (режим правки).
4) UI: чип «профиль» в шапке чата (bridge-событие profiles).

## Критерии приёмки
тесты зелёные; профиль из .colleague/agents/*.md меняет набор инструментов в live-запросе.

## Сообщение коммита
`feat: agent profiles with per-profile tool policy`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Decides which tools a profile may see. Read-only tools are always allowed;
 * write tools additionally require the edit mode, exactly as today. A profile
 * narrows the advertised set but never widens write access.
 */
public final class ToolPolicy {
    /**
     * Filters the advertised tool list for one profile.
     *
     * @param theProfile profile to apply, null means the default agent
     * @param theAll full tool list currently offered
     * @return the filtered list, order preserved
     */
    public List<ToolSpec> allowed(final AgentProfile theProfile, final List<ToolSpec> theAll);
}
```

## Где искать информацию
- PARITY-PLAN.md -> F3; RESEARCH-extensibility-mechanics.md §3; AgentMode.java:8-35
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

## Итог реализации (2026-10-28)
Выполнено. Коммиты: `64180a7` (test, красный: AgentProfileLoaderTest + ProfileToolPolicyTest) →
`6f1ddd8` (feat: agent profiles with per-profile tool policy — core-пакет) → `6b02b8e` (test,
красный: маршрутизация чипа + секция profiles) → `a720550` (feat: profile chip and per-profile
tool policy in the chat panel). done_commit=`a720550`.

- Пакет `com.mad.edt.colleague.agents` (core, добавлен в Export-Package): `AgentProfile`
  (name/description/system/modelName/maxSteps/mode PRIMARY|SUBAGENT|ALL/toolNames/hidden),
  `AgentProfileLoader` (`.colleague/agents/*.md`, frontmatter через общий парсер PP-F0; тело —
  системный промпт; `max-steps`, `mode`, `tools`, `hidden`, `model`), `ToolPolicy`
  (`allowed(profile, specs)` / `allows(profile, name)` / `isWriteTool`).
- Политика: профиль только СУЖАЕТ список, который уже предложил воркспейс (список и так
  формируется по режиму: readOnly vs withWrite), поэтому расширяет write-доступ физически не
  может — это закреплено тестом `profileNeverWidensTheOfferedSet`.
- Врезка в `ChatPanel.runAgent`: `pickedProfile()` → фильтрация инструментов через ToolPolicy,
  `max-steps` профиля вместо `AGENT_MAX_STEPS`, `model` профиля вместо модели вкладки, тело
  профиля дописывается в системную часть поверх общих правил.
- UI: чип `профиль: …` в шапке (`btnprofile`, клик листает обычный→профили→обычный),
  bridge-команда `pickProfile` → `PageCommandHandler.Actions.pickProfile`; секция `profiles`
  события `catalog` (F9) заполняется реальными профилями, скрытые (`hidden: true`) в чип не
  попадают; `ColleagueView.profileCatalog()` грузит `.colleague/agents` первого открытого
  проекта один раз за сессию вида (общий `colleagueDirectory`).
- Отклонения: (1) под фильтр профиля попадают и служебные инструменты (`update_plan`,
  `remember`, `load_skill`) — если профиль назвал свой список, он ограничивает всё, что видит
  модель, иначе «только read-only» профиль всё равно получил бы планировщик; (2) профиль с
  `mode: subagent` не может быть выбран чипом (фолбэк к обычному агенту) — subagent-режим
  включается в F4; (3) в красных тестах F3/`PageCatalogEventsTest` были собственные опечатки
  (неверная арность `SkillDefinition`, `null` вместо `List.of()`) — поправлены в feat-коммитах,
  поведение продукта не меняли.
