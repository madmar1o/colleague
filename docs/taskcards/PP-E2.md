# PP-E2 — Explain-selection / generate-method из меню редактора

Веха: M27 | Ветка: `feature/pp-e2-editor-actions` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-E2`
Зависимости: PP-F3 (профиль read-only)

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-E2) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M27.

## Что сделать (пошагово)
1) Красный тест EditorActionPromptsTest: действия «Объяснить выделение»/«Сгенерировать метод» формируют промпт с выделенным кодом и профилем без write-инструментов.
2) plugin.xml (UI): новые команды org.eclipse.ui.popupMenus, target org.eclipse.ui.editorss; обработчики в ui.internal.handlers по образцу AskSelectionHandler.
3) Вызов ColleagueView.stageQuestion с готовым промптом + профиль read-only (из PP-F3).

## Критерии приёмки
тесты зелёные; в редакторе BSL пункт контекстного меню отправляет выделение в чат с read-only профилем.

## Сообщение коммита
`feat: explain selection in the editor + feat: generate method from selection (два атомарных коммита в одной ветке)`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
Каждый handler — Javadoc: какой промпт формирует, какой профиль подставляет.
```

## Где искать информацию
- PARITY-PLAN.md -> E2; образец — AskSelectionHandler; stageQuestion — ColleagueView.java:113,122,366
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
