# PP-P3 — Ленивое создание браузера и отложенная индексация на старте

Веха: M21 | Ветка: `feature/pp-p3-lazy-startup` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-P3`
Зависимости: нет

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-P3) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M21.

## Что сделать (пошагово)
1) Красный тест ColleagueStartupDeferralTest: сразу после createPartControl (момент < 2 с) ни один Browser не создан, Job «Colleague: project index» не стартовал (состояние WAITING).
2) ColleagueView.openContact (~513): создание ChatPanel/Browser перенести в Display.timerExec(0,...) — НЕ внутри колбэков браузера (иначе дедлок).
3) ColleagueView.java ~184: Job индексации — schedule с задержкой 30 с и setSystem(true).

## Критерии приёмки
тест зелёный; в живом EDT окно Colleague появляется без блокировки UI на минуты; индексация стартует через 30 с.

## Сообщение коммита
`fix: defer browser creation and project index away from startup`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
Новые приватные методы — Javadoc с объяснением почему отложен запуск (дедлок колбэков браузера).
```

## Где искать информацию
- PARITY-PLAN.md -> P3; RESEARCH-perf-audit.md п.1.6; урок про дедлок — session-notes.md:57-63
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

## Итог реализации (2026-10-28)
Выполнено. Коммиты: `618f968` (test, красный) → `c1c5f7b` (fix: defer browser creation and project index away from startup).

- Новый seam `StartupDeferral` (ui.internal): `INDEX_JOB_DELAY_MS=30_000`,
  `scheduleIndexJob(...)` — job с `setSystem(true)` и задержкой 30 с; `defer(...)` —
  тяжёлая сборка чат-страницы после первой отрисовки (asyncExec).
- `ColleagueView`: job «Colleague: project index» запускается через seam; `openContact`
  (пути UI: клик по списку, автооткрытие, тест phase2) создаёт вкладку сразу, а `ChatPanel`
  с браузером строит после отрисовки; синхронный `openContactNow` — для staged-вопросов
  и selftest'ов, которым панель нужна сразу.
- **Отклонение 1:** тест проверяет seam, которым теперь сшит `createPartControl`, а не сам
  `createPartControl` — headless-юнит не может поднять workbench-страницу с SWT.
- **Отклонение 2:** в целевом рантайме EDT у `Job` нет константы `SCHEDULED` (есть
  `SLEEPING/WAITING/RUNNING`) — тест паркует job в `SLEEPING|WAITING` и запрещает `RUNNING`.
- **Отклонение 3:** вместо `Display.timerExec(0,...)` — `asyncExec`: задача идёт сразу после
  текущего прогона событий, что и есть «после первой отрисовки» для открываемой вкладки.
- Не проверено живым прогоном EDT: отсутствие фриза вкладки и старт индекса не раньше 30 с.
