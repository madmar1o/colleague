# PP-F5 — MCP-клиент (stdio + HTTP) и адаптер инструментов

Веха: M25 | Ветка: `feature/pp-f5-mcp-client` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-F5`
Зависимости: PP-F0

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-F5) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M25.

## Что сделать (пошагово)
1) Красные тесты против in-process fake-сервера (сокеты в тесте): McpClientHandshakeTest — handshakeListsTools(), callToolTranslatesResultText(); McpToolAdapterTest — MCP-инструмент попадает в advertised tools с префиксом mcp_<server>_<tool> и проходит подтверждение записи если объявлен write-подобным (эвристика annotations.readOnlyHint, иначе write).
2) core пакет com.mad.edt.colleague.mcp: McpServerConfig.java (имя, command[] или url, enabled), McpSettings.java (Preferences node com.mad.edt.colleague.mcp, ключ servers.json), McpClient.java (JSON-RPC: initialize -> tools/list -> tools/call; без resources/sampling/oauth), McpToolAdapter.java (маппинг в существующий ToolSpec + диспетчер AgentLoop), McpCatalog.java (reload-политика: один источник на все серверы; MCP-инструменты НЕ перекрывают встроенные).
3) Конфиг UI: секция «MCP-серверы» на странице провайдеров + bridge-методы listMcp/saveMcp/testMcp.

## Критерии приёмки
тесты зелёные; живой MCP-сервер (stdio или http) отдаёт инструменты агенту; конфликт имён со встроенным инструментом не побеждает MCP.

## Сообщение коммита
`feat: mcp client for external tool servers`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Minimal MCP client: JSON-RPC over a spawned stdio process or an HTTP
 * endpoint. Performs the initialize handshake, then tools/list and
 * tools/call. Kept deliberately small: no resources, no sampling, no oauth.
 */
public final class McpClient {
    /**
     * Opens the connection and negotiates the tool catalog.
     *
     * @return discovered tools, empty when the server exposes none
     * @throws McpException when the handshake or listing fails
     */
    public List<McpToolInfo> discover() throws McpException;
    /**
     * Invokes one remote tool.
     *
     * @param theName tool name as discovered
     * @param theArguments JSON object of arguments
     * @return concatenated text content of the result
     * @throws McpException on transport or protocol failure
     */
    public String call(final String theName, final JsonObject theArguments) throws McpException;
}
```

## Где искать информацию
- PARITY-PLAN.md -> F5; RESEARCH-extensibility-mechanics.md §4; паттерн сетевых тестов — QdrantScrollContractTest
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

## Итог реализации (2026-10-29)
Выполнено. Коммиты: `5459420` (test, красный: McpClientHandshakeTest + McpToolAdapterTest) →
`91b0d76` (feat: mcp client for external tool servers — core пакет) → `3482323` (feat: секция
MCP-серверов на странице провайдеров + удалённые инструменты в каталоге агента) → `e0f48c5`
(test: приёмка — живой stdio-сервер кормит настоящий AgentLoop). done_commit=`91b0d76`
(коммит с сообщением из карточки).

- core пакет `com.mad.edt.colleague.mcp` (экспортирован в MANIFEST): `McpServerConfig`
  (имя, command[] или url, enabled + `sameSettings` для перезагрузки), `McpSettings`
  (Preferences-нода `com.mad.edt.colleague.mcp`, ключ `servers.json`, upsert/remove/load),
  `McpClient` (JSON-RPC initialize→initialized→tools/list→tools/call; stdio — спавн процесса,
  HTTP — POST; без resources/sampling/oauth), `McpToolInfo` (record, readOnly из
  `annotations.readOnlyHint`, иначе write), `McpToolAdapter` (имя `mcp_<server>_<tool>`,
  write-подобные требуют подтверждения `Confirmation`, `isParallelSafe` = readOnly),
  `McpCatalog` (один источник на все серверы; перезагрузка при смене значений настроек;
  недоступный сервер молча выпадает, громкая ошибка — на кнопке «Проверить»; конфликт имени
  со встроенным — MCP пропускается, не переименовывается), `McpException`, `ToolBridge`.
- UI: секция «MCP-серверы» в оверлее страницы провайдеров (таблица, вкл/выкл, изменить/удалить,
  форма имя+url+команда, кнопка «Проверить» с показом инструментов); bridge-методы
  `listMcp`/`saveMcp`/`removeMcp`/`testMcp`; `settingsData` теперь несёт `mcpServers`.
  Врезка в `ChatPanel.runAgent`: каталог спрашивается на каждый прогон после локальных
  инструментов (имя встроенного всегда побеждает конфликт), проходит через `ToolPolicy`
  (профиль + прунинг), попадает и в делегата-субагента; подтверждение write-подобного вызова —
  модалка `MessageDialog` на UI-потоке (тот же паттерн, что у файловых инструментов).
- Отклонения: (1) `ToolBridge` — отдельный top-level интерфейс: Java запрещает классу
  реализовывать собственный вложенный интерфейс (ошибка компилятора «cycle detected»);
  (2) reload-политика каталога — сравнение значений списка серверов (`sameSettings` вместо
  счётчика версий: нет лишнего состояния, тесты не требуют Preferences); (3) write-подобные
  удалённые инструменты предлагаются только в прогоне, где есть локальные write-инструменты
  (read-only прогон получает только readOnly-инструменты сервера); (4) test-fake HTTP-сервера —
  голый `ServerSocket` (пакет `com.sun.net.httpserver` недоступен OSGi-фрагменту тестов),
  stdio-fake — single-file запуск `java <файл>.java` (в OSGi-тест рантайме `java.class.path`
  не содержит классы тестов); (5) у HTTP-транспорта `notifications/initialized` отправляется
  POST-ом без id, fake отвечает ошибкой — клиент её игнорирует, протокол этого не запрещает.
