# AG-1 — Разворачиваемый трассировочный блок вызванных инструментов

Ветка: `feature/ag1-tool-trace` | Борд: Qdrant `oc_plans`, id=`6956c6ef-49e4-49c8-af46-4653fb96ba56`
Зависимости: `fix/m26-chat-no-blocking` (там `PageAgentObserver` и коагуляция моста)

## Статус
**pending** (планирование).

## Что сделать (пошагово)
1) Красный тест `PageAgentObserverTest` (расширить): чип обязан нести полный след вызова —
   `args` (JSON аргументов целиком), `result` (превью результата с обрезкой, лимит ~400 симв.),
   `elapsed` (мс). Ассерт по `onToolCall(2,"search_code","{...}","1234 chars",3977L)`.
2) `PageAgentObserver.onToolCall`: в чип добавить `args`, `result` (обрезанное превью),
   `elapsed`; `onRound` вместо одиночной строки статуса открывает трассу раунда: событие
   `trace` `{round, calls:N}`; чипы раунда уходят внутрь этой трассы (порядок очереди моста
   гарантирует: сначала `trace`, потом `tool`-чипы).
3) `ChatPanel.pushToolChip` -> `pushNow("tool")` без изменений; новое событие `trace` — тем же
   путём `pushNow("trace", ...)`.
4) `chat.html`: строка `.tools` (~622-640) становится сворачиваемым блоком `.trace` по образцу
   `.thought` (~551-579): заголовок «Раунд N — K инструментов ▸», клик по заголовку сворачивает/
   разворачивает список чипов; клик по чипу разворачивает детали (args/result/elapsed) под чипом.
   Стиль `+`/`−` и `tarrow` переиспользовать.

## Критерии приёмки
После агентного хода в ленте — блок на раунд: виден список вызванных инструментов, разворот
деталей по чипу (аргументы + превью результата + время), повторный клик сворачивает.
`PageAgentObserverTest` зелёный, `node --check` извлечённого JS зелёный, `clean verify` зелёный.

## Сообщение коммита
`feat: разворачиваемый трассировочный блок вызванных инструментов в ленте чата`

## Javadoc-каркас
```java
/**
 * Opens a trace block for one model round-trip on the page: the block header
 * states the round and how many tool calls it asked for, and the chips of
 * that round nest inside it, each expandable to the arguments and the result
 * preview the call left behind.
 *
 * @param theRound 1-based round-trip number
 * @param theRequestedToolCalls how many calls the round asked for
 */
@Override
public void onRound(final int theRound, final int theRequestedToolCalls)
```
