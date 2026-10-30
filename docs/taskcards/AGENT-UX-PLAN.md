# План: UX агента в чате — трассировка, режимы, контакты, бюджет шагов

Ветки: `feature/agent-ux-batch` (карточки) | Реализация — отдельными ветками `feature/ag[1-4]-*`
Борд: Qdrant `oc_plans`, project=`Colleague`, status=`pending` (планирование):
AG-1 `6956c6ef-49e4-49c8-af46-4653fb96ba56` (карточка `AG-1-tool-trace.md`),
AG-2 `c2bd293b-f0b9-4e89-b07a-48e02b759e74` (карточка `AG-2-modes.md`),
AG-3 `6f294f7b-5d20-4812-ab6f-bc3ae20462d9` (карточка `AG-3-agent-contacts.md`),
AG-4 `3ceda6b8-674e-4b67-8769-85c57fab3e0c` (карточка `AG-4-step-budget.md`).

Зависимость всех четырёх: ветка `fix/m26-chat-no-blocking` (там `PageAgentObserver`,
`UiTicks`, `StreamBatcher.drain`, коагуляция моста) — реализацию ветвить после её вливания.

## Порядок работ
1. **AG-4** (бюджет шагов) — чинит падение длинных агентных задач, нужно раньше остальных.
2. **AG-1** (трассировка инструментов) — опирается на `PageAgentObserver` из fix-ветки.
3. **AG-2** (режимы План/Исполнение) — меняет семантику `AgentMode`, от него зависит AG-3.
4. **AG-3a/AG-3b** (контакты агентов + шестерёнка) — витрина поверх готового `profileCatalog()`.

## Первоисточник (факты, проверенные по коду)
- `AgentLoop.java:138` — `while(steps < maxSteps)`; `:192` — при исчерпании цикл возвращает
  текст `"Step budget exhausted after N round-trips"` как финальный ответ (completed=false).
- `ChatPanel.java:85` — `AGENT_MAX_STEPS = 8`; `:1333-1334` — приор `profile.getMaxSteps()>0`.
- `AgentProfile.java:40-42` — `maxSteps=0` означает «оставить дефолт чата»; `:16-20` — `Mode`
  профиля (ALL/CHAT/SUBAGENT) — где профиль может запускаться.
- `AgentLoop.java:128-134` — `run(List<ChatMessage>)` продолжает открытую переписку: механизм
  для кнопки «продолжить» уже есть.
- `PlanBoard.java:18` — `MAX_STEPS = 12` (потолок борда плана инструмента `update_plan`).
- `PageAgentObserver.onToolCall` → чип `{name, detail, state, file}` → `ChatPanel.pushToolChip`
  → `pushNow("tool")`; страница: `chat.html` `setTools`/`addTool` (~622-640) — одна строка чипов,
  без деталей. `onRound` → `setStatus` («Раунд N: модель просит инструменты, K»).
- `AgentMode` (`com.mad.edt.colleague.agent.AgentMode`) — глобальный булев переключатель;
  `ChatPanel.toggleEdit` :782, кнопка «Правка: вкл» :992/:1529, обработчик страницы `toggleEdit` :607.
- Разделение read-only/запись уже есть: `ChatPanel.java:1250` (`localWrites` по именам
  `write_file`/`edit_file`), `:1255-1257` (в read-only прогоне остаются только read-only
  удалённые инструменты), `ToolPolicy.allows(profile, name)`.
- Профили уже каталогизированы: `ChatPanel.profileCatalog()` :924, `pickProfileName` :956,
  `setProfileSupplier` :342 — база для «списка контактов» есть.
