# PP-P5 — Пустой финальный раунд — не платить дважды

Веха: M22 | Ветка: `feature/pp-p5-repair-round` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-P5`
Зависимости: нет

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-P5) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M22.

## Что сделать (пошагово)
1) Красный тест AgentLoopEmptyFinalTest (scripted-модель): раунд1 tool_call, раунд2 пустой thinking -> ассерт: третий вызов идёт с think=false И maxTokens >= 2*ROUND_TRIP_OUTPUT_TOKENS.
2) AgentLoop.java 145-154: repair-ход поднимает maxTokens до 8192 и ставит think(false); добавить явный маркер repair-раунда в observer-лог раундов (статистика F24).

## Критерии приёмки
тест зелёный; в e2e-трейсе раундов repair-ход помечен и идёт с увеличенным бюджетом.

## Сообщение коммита
`fix: widen the repair round budget`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
Метод repair-хода — Javadoc: когда срабатывает, какой бюджет, почему think off.
```

## Где искать информацию
- PARITY-PLAN.md -> P5; AgentLoop.java:128-130,145-154; замеры e2e 53-147 с — TASKS-quadrant.md:41
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
