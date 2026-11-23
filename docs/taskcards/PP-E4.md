# PP-E4 — Клик путь:строка в ответе чата открывает редактор

Веха: M27 | Ветка: `feature/pp-e4-open-in-editor` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-E4`
Зависимости: PP-P1 (мост уже умеет push), PP-E1 (не обязателен)

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-E4) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M27.

## Что сделать (пошагово)
1) Красный тест OpenFileLinkTest: токен вида path:line в ответе парсится в ссылку; bridge-команда openInEditor(path,line) валидирует путь внутри воркспейса.
2) chat.html: рендерить путь:строка кликабельным.
3) UI-бандл: bridge-метод openInEditor -> IDE.openEditor(page, file) с переходом на строку; валидация как WorkspaceTools.java:39-50.

## Критерии приёмки
тест зелёный; клик по пути в ответе открывает файл в редакторе на нужной строке; путь вне воркспейса отклоняется.

## Сообщение коммита
`feat: open referenced files from chat answers`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
openInEditor — Javadoc: валидация границ воркспейса, поведение при несуществующем файле.
```

## Где искать информацию
- PARITY-PLAN.md -> E4; защита пути — WorkspaceTools.java:39-50
- Конвенции репозитория: AGENTS.md в корне этого репозитория.
- Карточки остальных задач плана: docs/taskcards/ (в milestone-ветках вех).

## Принципы проекта (нарушение = задача не засчитана)
1. TDD red-first: сначала красный тест, потом реализация, потом зелёный. Тестовые фрагменты:
   com.mad.edt.colleague.tests / com.mad.edt.colleague.context.tests (JUnit 5).
2. Сборка: cd "colleague"; $env:JAVA_HOME=$env:JAVA_HOME_17; .\mvnw.cmd -B clean verify.
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
