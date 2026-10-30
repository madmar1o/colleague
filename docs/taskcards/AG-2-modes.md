# AG-2 — Режимы «План» и «Исполнение» вместо переключателя «Правка: вкл»

Ветка: `feature/ag2-modes` | Борд: Qdrant `oc_plans`, id=`c2bd293b-f0b9-4e89-b07a-48e02b759e74`
Зависимости: `fix/m26-chat-no-blocking`; на неё опирается AG-3 (профили-контакты)

## Статус
**pending** (планирование).

## Первоисточник
- `AgentMode` (`com.mad.edt.colleague.agent.AgentMode`) — глобальный булев тумблер;
  `ChatPanel.toggleEdit` :782, кнопка «Правка: вкл/выкл» :992/:1529, обработчик страницы
  `toggleEdit` :607, событие страницы `mode` :802.
- Разделение read-only/запись уже в коде: `ChatPanel.java:1250` (`localWrites` по именам
  `write_file`/`edit_file`), `:1255-1257` (read-only прогон оставляет только read-only удалённые
  инструменты), `ToolPolicy.allows(profile, name)`; инструмент плана `update_plan` (:1279),
  борд `PlanBoard` (`PlanBoard.java:18`, `MAX_STEPS=12`).

## Что сделать (пошагово)
1) Красный тест `AgentModeTest` (tests-бандл, пакет `com.mad.edt.colleague.agent`):
   трёхсостояние `Ask` (болтовня без инструментов) / `Plan` (инструменты только чтение +
   `update_plan`) / `Execute` (полный набор); смена состояния сохраняется между сессиями
   панели; старый API `isOn()/toggle()` — совместимость: `toggle()` гоняет цикл
   Ask->Execute->Ask.
2) `AgentMode`: булево поле -> enum `ChatMode { ASK, PLAN, EXECUTE }` + хранение; экспорт enum
   в `Export-Package` пакета `com.mad.edt.colleague.agent` (сейчас x-internal — проверить MANIFEST).
3) `ChatPanel`: вместо `toggleEdit` — `setMode(String)` из страницы; сборка инструментов в
   `runAgent` по режиму: `PLAN` — исключить `write_file`/`edit_file`/записывающие MCP, оставить
   `update_plan`/чтение; `ASK` — агент не запускается вовсе (текущий выключенный режим);
   `EXECUTE` — как нынешняя «Правка: вкл».
4) Страница: сегментированный переключатель из трёх кнопок в шапке вместо одной
   («Спросить | План | Исполнение»), состояние — событие `mode` с именем режима; SWT-фолбэк —
   те же три состояния кнопки.

## Критерии приёмки
В режиме «План» агент собирает план (`update_plan`) и читает проект, но не может вызвать
`write_file`/`edit_file` (проверяется тестом сборки инструментов); в «Исполнение» — полное
поведение нынешней «Правки»; «Спросить» — чат без инструментов. `AgentModeTest` зелёный,
`clean verify` зелёный.

## Сообщение коммита
`feat: режимы Спросить/План/Исполнение вместо тумблера правки`

## Javadoc-каркас
```java
/**
 * What the chat may do with the workspace on the next turn: plain conversation
 * without tools, a read-only planning pass that may only observe and lay out
 * a plan, or full execution including writes.
 */
public enum ChatMode {
    /** conversation only, no tools offered at all */
    ASK,
    /** tools that only read the workspace plus the plan board */
    PLAN,
    /** the full tool set, writes included */
    EXECUTE
}
```
