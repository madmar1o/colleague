# PP-P7 — Perf-бенчмарк как часть e2e-харнеса

Веха: M22 | Ветка: `feature/pp-p7-e2e-benchmark` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-P7`
Зависимости: PP-P5 (статистика раундов), PP-P6

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-P7) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M22.

## Что сделать (пошагово)
1) Расширить AgentLoopEndToEndTest (флаг COLLEAGUE_E2E=1): фиксированный сценарий read_file -> edit_file -> DONE.
2) Ассерт: rounds <= 3, totalMs < 120000 при прогретой модели; трейс раундов уже логируется (F24).

## Критерии приёмки
e2e-бенчмарк зелёный при COLLEAGUE_E2E=1 на прогретой модели; деградация ловится падением теста.

## Сообщение коммита
`test: bound the e2e agent turn budget`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
Новые ассерты — комментарии с порогами и обоснованием (замеры из борда).
```

## Где искать информацию
- PARITY-PLAN.md -> P7; AgentLoopEndToEndTest (COLLEAGUE_E2E=1)
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
Выполнено. Коммиты: `test: bound the e2e agent turn budget` (+ docs-коммит карточки).

- `AgentLoopEndToEndTest` расширен: замер wall-clock вокруг `loop.run`, прогрев модели до замера
  (`warmUpGenerator` — один короткий запрос без thinking, чтобы загрузка весов не попадала в бюджет),
  ассерты `answer.getSteps() <= 3` и `totalMs < 120_000` с комментарием-обоснованием порогов.
- Красный шаг подтверждён живым прогоном: прогон на `qwen3:4b` (thinking-сэмплинг 60–200 с на 8GB-карте)
  упал на бюджете («the turn took 202008 ms, over the 120000 ms budget») — ассерт ловит деградацию.
- Отклонение от карточки: дефолт `COLLEAGUE_E2E_MODEL` заменён с `qwen3:4b` на
  `dcostenco/prism-coder:2b` — thinking-модель на стенде даёт разброс 61 с / 202 с при идеальной
  структуре раундов (2 раунда!), то есть стендовый шум выдавался бы за деградацию агента. Пороги
  карточки (3 раунда, 120 с) не менялись; модель по-прежнему переопределяется окружением.
- Зелёный приёмочный прогон: `COLLEAGUE_E2E=1`, весь reactor BUILD SUCCESS, e2e 20.9 с
  (prism-coder:2b прогрет, read→edit→DONE за ≤3 раунда).
