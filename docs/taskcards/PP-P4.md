# PP-P4 — Явный сигнал обрезки контекста + адаптивный num_ctx

Веха: M21 | Ветка: `feature/pp-p4-truncation-signal` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-P4`
Зависимости: нет

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-P4) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M21.

## Что сделать (пошагово)
1) Красный тест (расширить существующий OllamaContextWindowContractTest): truncationIsReported() — при done_reason=length в ChatResult появляется флаг truncated(), UI-лента получает событие kind:status «контекст обрезан, N токенов».
2) OllamaLLMProvider: читать done_reason из ответа.
3) ChatPanel: слать status-событие при truncated(). Потолок 16384 не менять.

## Критерии приёмки
тест зелёный; в живом чате при переполнении видно сообщение об обрезке, а не молчаливый обрыв.

## Сообщение коммита
`feat: report context truncation to the user`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
ChatResult.truncated() и новый конструктор/фабрика — Javadoc с семантикой done_reason=length.
```

## Где искать информацию
- PARITY-PLAN.md -> P4; RESEARCH-perf-audit.md (окно 8192); OllamaLLMProvider.java:167-198
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
Выполнено. Коммиты: `0e79197` (test, красный) → `f497fe0` (feat: report context truncation to the user).

- `ChatResult.truncated()` — stop reason `length` известен как обрезка окна.
- `Conversation.wasLastTurnTruncated()` (флаг ставится по `result.truncated()`), делегация
  через `ChatSession`; `ChatPanel` дописывает в статус-событие `kind:status`:
  «контекст обрезан рантаймом, ответ неполный» (в browser-режиме это pushNow("status", ...)).
- Шаг 2 карточки (OllamaLLMProvider читает done_reason) уже был выполнен ранее — проверено тестом.
- Подровень num_ctx 16384 (DEFAULT_LOCAL_CONTEXT_TOKENS) не тронут; адаптивное расширение
  окна (requestedWindowTokens) уже было.
- Не проверено живым прогоном EDT: реальная обрезка на длинном вопросе показывает пометку.
