# PP-F2 — Slash-команды пользователя из markdown-файлов проекта

Веха: M23 | Ветка: `feature/pp-f2-slash-commands` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-F2`
Зависимости: PP-F0

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-F2) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M23.

## Что сделать (пошагово)
1) Красные тесты: CommandLoaderTest (parsesTemplateAndArguments(): тело с $ARGUMENTS подставляет хвост строки пользователя; missingFileYieldsEmpty()), SlashCommandParseTest (splitsNameAndArgs(): кириллические имена разрешены).
2) core пакет com.mad.edt.colleague.commands: CommandDefinition.java (name, description, template, agentName, subagent), CommandLoader.java (каталог <workspace>/.colleague/commands/*.md, frontmatter + тело-шаблон с $ARGUMENTS), SlashCommands.java (match(theLine) -> Invocation c подставленным шаблоном или null).
3) UI: ChatPanel перед отправкой дёргает SlashCommands.match; chat.html — подсказки по слэшу (список имён из bridge-события commands при загрузке вкладки).

## Критерии приёмки
тесты зелёные; в живом чате команда с аргументами подставляет их в шаблон и уходит в модель; неизвестная слэш-строка остаётся текстом.

## Сообщение коммита
`feat: user slash commands from project markdown files`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Loads user commands from <workspace>/.colleague/commands/*.md:
 * a frontmatter header (description, optional agent and subagent flags)
 * over a template body where ARGUMENTS receives the rest of the
 * user line.
 */
public final class CommandLoader {
    /**
     * Loads every command file below the given directory.
     *
     * @param theCommandsDir root directory, missing directory yields empty list
     * @return loaded commands sorted by name
     * @throws java.io.IOException on file system failures
     */
    public List<CommandDefinition> loadAll(final File theCommandsDir) throws IOException;
}

/**
 * Turns one chat input line into a command invocation when it starts with
 * a slash and names a loaded command; otherwise the line is plain text.
 */
public final class SlashCommands {
    /**
     * Matches the input against the command catalog.
     *
     * @param theLine raw user input
     * @return invocation with filled template, or null when the
     *         line is not a known slash command
     */
    public Invocation match(final String theLine);
}
```

## Где искать информацию
- PARITY-PLAN.md -> F2; RESEARCH-extensibility-mechanics.md §2
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
Выполнено. Коммиты: `dd4d9d5` (test, красный: CommandLoaderTest + SlashCommandParseTest) →
`e8144ad` (feat: user slash commands from project markdown files). done_commit=`e8144ad`.

- Пакет `com.mad.edt.colleague.commands` (core, добавлен в Export-Package): `CommandDefinition`
  (name/description/template/agentName/subagent), `CommandLoader` (`.colleague/commands/*.md`,
  frontmatter через общий парсер PP-F0 `Frontmatter`, тело — шаблон с `$ARGUMENTS`),
  `SlashCommands.match(line) -> Invocation` с подставленным шаблоном или null.
- `SlashCommands`: имя — до первого пробела, кириллические имена работают; нет плейсхолдера —
  аргументы дописываются хвостом через перенос строки; команда с обязательным `$ARGUMENTS` при
  пустом хвосте НЕ срабатывает (неполная строка остаётся текстом чата).
- UI: `ChatPanel.setCommandSupplier` — `sendQuestion` матчит строку перед отправкой, в модель
  уходит раскрытый промпт, в пузыре чата — исходный текст пользователя; неизвестная слэш-строка
  остаётся текстом. `chat.html` — подсказки по слэшу: каталог приходит bridge-событием `commands`
  при открытии вкладки (`pushCommandCatalog` в `bridgeReady`), панель подсказок над композером,
  клик дописывает имя.
- Проводка: `ColleagueView.commandsCatalog()` грузит каталог один раз за сессию вида из
  `.colleague/commands` первого открытого проекта; общий хелпер `colleagueDirectory(name)`
  для `.colleague/skills` и `.colleague/commands` (убрал дублирование из F1).
- Отклонения: (1) поля `agentName`/`subagent` загружаются и прокинуты в модель данных, но
  проводка агента-профиля под команду — вне рамок F2 (в карточке шага UI-проведки профиля нет);
  (2) вместо отдельного прохода по проектам в каждом каталоге — общий `colleagueDirectory`.
