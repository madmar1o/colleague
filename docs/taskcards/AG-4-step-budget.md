# AG-4 — Бюджет шагов агента: «Step budget exhausted after 8 round-trips»

Ветка: `feature/ag4-step-budget` | Борд: Qdrant `oc_plans`, id=`3ceda6b8-674e-4b67-8769-85c57fab3e0c`
Зависимости: `fix/m26-chat-no-blocking` (вливать первой)

## Статус
**pending** (планирование). Начинать с `git log --oneline main..HEAD`.

## Что сделать (пошагово)
1) Красный тест `AgentLoopBudgetTest` (tests-бандл, пакет `com.mad.edt.colleague.agent`):
   фейковый провайдер всегда просит инструменты; ассерты:
   - цикл обрывается ровно на `maxSteps` (сейчас `AgentLoop.java:138`), текст ответа —
     «исчерпание» и `completed=false` (`AgentLoop.java:192`);
   - НОВОЕ: при исчерпании выполняется один «сворачивающий» раунд БЕЗ инструментов
     (`request.tools(пусто)`), его текст становится ответом с пометкой о неполноте;
   - НОВОЕ: `AgentAnswer` несёт оборванное состояние переписки (список сообщений) для продолжения.
2) `AgentLoop.run`: после выхода из цикла — финальный wrap-up раунд (без tools,think off,
   budget `REPAIR_ROUND_OUTPUT_TOKENS`); в `AgentAnswer` добавить поле `conversation`
   (сообщения на момент остановки). Дефолт `DEFAULT_MAX_STEPS` 8 -> 12 (совпадает с
   `PlanBoard.MAX_STEPS`, `PlanBoard.java:18`).
3) `ChatPanel`: хранить последнюю оборванную переписку хода (`ourLastAgentTurn`);
   новый метод страницы `continueTurn` -> новый `AgentLoop(...).run(сохранённая переписка)`
   с тем же `TurnController`-билетом; в ответе чата — строка «бюджет исчерпан, продолжено».
4) Отображение: `PageAgentObserver.onRound` уже шлёт статусы; при исчерпании — событие
   `status` «Бюджет шагов исчерпан (N раундов, M инструментов) — нажми «Продолжить»»;
   `chat.html` — кнопка «Продолжить» в ленте, шлёт `apiCall("continueTurn", {})`.

## Критерии приёмки
Длинная агентная задача не обрывается голой строкой ошибки: в ленте финальный текст-сводка +
кнопка «Продолжить», после нажатия цикл идёт дальше на сохранённой переписке
(`AgentLoop.java:128-134` уже умеет). Тесты `AgentLoopBudgetTest` зелёные; `clean verify` зелёный.

## Сообщение коммита
`feat: продолжение агентного хода при исчерпании бюджета шагов`

## Javadoc-каркас
```java
/**
 * Runs the task to its budget and, when the budget ends mid-work, asks the
 * model one closing round-trip without tools to summarize the partial state.
 * The interrupted conversation travels in the answer so the caller can offer
 * a continuation over exactly that history.
 *
 * @param theConversation messages to start from, system message first
 * @return the final answer, carrying the open conversation when truncated
 */
public AgentAnswer run(final List<ChatMessage> theConversation) throws LLMException
```
