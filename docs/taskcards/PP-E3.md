# PP-E3 — Инструменты агента find_metadata / read_metadata

Веха: M27 | Ветка: `feature/pp-e3-metadata-tools` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-E3`
Зависимости: нет

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-E3) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M27.

## Что сделать (пошагово)
1) Красный тест MetadataToolsTest на fixture-проекте (копия .mdo-дерева в tmp): findsObjectByName() (head-read .mdo — паттерн есть в RESEARCH-feature-audit.md п.3), listsRegistersWithDimensions().
2) core пакет com.mad.edt.colleague.agent: MetadataTools.java — регистрация как у WorkspaceTools; find_metadata (имя/тип -> путь в дереве конфигурации + свойства кратко), read_metadata (реквизиты/табличные части, бюджет 4000 симв.).

## Критерии приёмки
тесты зелёные; в live-чате запрос на поиск объекта даёт find_metadata в трейсе раундов.

## Сообщение коммита
`feat: configuration metadata tools for the agent`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Answers configuration-tree questions from metadata files: locate an
 * object by name, read its attributes and tab sections. Read-only, always
 * offered, bounded by the shared tool result budget.
 */
public final class MetadataTools { ... }
```

## Где искать информацию
- PARITY-PLAN.md -> E3; образец describe_configuration — WorkspaceTools.java:444-479; потолок MAX_TOOL_RESULT_CHARS — AgentLoop.java:26
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
