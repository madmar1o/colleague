# PP-E1 — Контекст активного редактора для автоконтекста

Веха: M27 | Ветка: `feature/pp-e1-active-editor` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-E1`
Зависимости: нет

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-E1) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M27.

## Что сделать (пошагово)
1) Красный тест ActiveEditorContextTest: fake-редактор с файлом .bsl -> поставщик контекста возвращает имя файла + окно +-40 строк вокруг каретки; при закрытии редактора контекст пустеет.
2) UI-бандл пакет com.mad.edt.colleague.ui.internal: ActiveEditorWatcher.java — подписка на IPartListener (ACTIVATED) workbench-страницы; EDT-редакторы BSL — ITextEditor, подхватываются стандартно.
3) Врезка в ChatPanel.ourAutoContextSupplier (~72-75): конкатенация ConfigurationOverview + ActiveEditorWatcher в общий бюджет 3000 симв.

## Критерии приёмки
тест зелёный; вопрос без выделения получает окно вокруг каретки в автоконтексте.

## Сообщение коммита
`feat: autocontext from the active editor`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Listens to workbench part activation and tracks the text editor the user
 * is looking at: file path, caret line and a bounded window of surrounding
 * lines. Feeds the autocontext supplier so every question knows where the
 * user is standing in the configuration tree.
 */
public final class ActiveEditorWatcher {
    /**
     * Registers part listeners on the given page.
     *
     * @param thePage workbench page to watch
     */
    public void attach(final IWorkbenchPage thePage);
}
```

## Где искать информацию
- PARITY-PLAN.md -> E1; AskSelectionHandler.java:22-45; врезка — ChatPanel.java:72-75
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
