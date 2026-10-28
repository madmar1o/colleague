# PP-F9 — Каталог команд/скилов/профилей на чат-страницу

Веха: M23 | Ветка: `feature/pp-f9-catalog-events` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-F9`
Зависимости: PP-P1 (pushEvents), PP-F2

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-F9) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M23.

## Что сделать (пошагово)
1) Красный тест моста PageCatalogEventsTest: при открытии вкладки страница получает событие catalog со списком команд/скилов/профилей.
2) ChatPanel: при создании вкладки слать pushEvents (из PP-P1) пакет catalog.

## Критерии приёмки
тест зелёный; в UI виден список доступных команд и скилов новой вкладки.

## Сообщение коммита
`feat: advertise commands and skills to the chat page`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
Новое bridge-событие — Javadoc формата payload catalog.
```

## Где искать информацию
- PARITY-PLAN.md -> F9
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
Выполнено. Коммиты: `09ffc97` (test, красный: PageCatalogEventsTest) → `45fc206`
(feat: advertise commands and skills to the chat page). done_commit=`45fc206`.

- Новый core-класс `com.mad.edt.colleague.chat.PageCatalogEvents.build(commands, skills)` —
  payload события `catalog`: массивы `commands` / `skills` / `profiles` с парами `name` +
  `description`; пустые секции — пустые массивы (не отсутствующие поля), `profiles` заполнится
  в вехе M24 (F3/F4), страница читает поле уже сейчас.
- `ChatPanel`: при готовности моста (`bridgeReady`) шлётся одно событие `catalog` вместо
  отдельного `commands` из F2 — DRY: подсказки по слэшу и строка каталога питаются из одного
  пакета (`pushCatalogEvent`, старый `pushCommandCatalog` удалён).
- `chat.html`: строка `команды: /… · скилы: …` над композером (`catalogline`, в `title` —
  описания), подсказки по слэшу берут список из того же события.
- Отклонения: (1) событие `commands` из F2 заменено общим `catalog` (иначе два пакета с одним
  содержимым); (2) в красном тесте был собственный дефект — `null` вместо `List.of()` в
  `SkillDefinition` (конструктор не принимает null-список инструментов), поправлен в том же
  feat-коммите, поведение продукта не менял.
