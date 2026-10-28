# PP-P1 — Push-доставка событий на чат-страницу вместо ожидания поллинга

Веха: M21 | Ветка: `feature/pp-p1-push-events` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-P1`
Зависимости: нет (первая задача плана)

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-P1) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M21.

## Что сделать (пошагово)
1) Красный тест BridgePushDeliveryTest (фрагмент com.mad.edt.colleague.tests, пакет com.mad.edt.colleague.ui.internal): fake-приёмник скриптов браузера; ассерт — после postEvent() скрипт colleagueEvents([...batch]) поставлен в очередь доставки БЕЗ вызова sync со страницы.
2) BridgeBrowser.java (ui-бандл, строки 139-158 — сейчас события только как ответ на sync): новый метод pushEvents(List<Object[]> theBatch) — timerExec(0,...) -> execute(window.colleagueEvents(...)); тот же путь доставки, что уже работает для syncPollViaLocation (BridgeBrowser.java ~291). При исключении execute события не теряются — они и так в batch-очереди (BridgeBrowser.java:24-40).
3) ChatPanel.java ~957-963: после каждого postEvent вызывать pushEvents вместо ожидания следующего sync.
4) chat.html: интервал sync поднять 1500 -> 4000 мс (fallback); обработчик window.colleagueEvents(batch) уже существует.

## Критерии приёмки
Задержка «ответ модели -> пузырь на странице» < 300 мс (замер самопроверкой colleague.browser.selftest); в out-логе маркеры [colleague] push delivered; тест BridgePushDeliveryTest зелёный; clean verify зелёный.

## Сообщение коммита
`feat: push events to the chat page instead of waiting for polling`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Queues an asynchronous push of pending events to the attached page.
 * Delivery goes through the browser script channel on the next UI tick;
 * when the script channel is not warm yet, the events stay in the
 * durable batch queue and are drained by the next synchronous reply.
 *
 * @param theBatch ordered events to deliver
 */
public void pushEvents(final List<Object[]> theBatch)
```

## Где искать информацию
- PARITY-PLAN.md -> ТРЕК P, P1; RESEARCH-perf-audit.md п.2 (мост 1.5 с)
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
