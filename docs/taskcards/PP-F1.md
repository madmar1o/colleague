# PP-F1 — Скилы: витрина + ленивая загрузка (экономия контекста)

Веха: M23 | Ветка: `feature/pp-f1-skill-showcase` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-F1`
Зависимости: PP-F0

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-F1) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M23.

## Что сделать (пошагово)
1) Красный тест SkillShowcaseTest: catalogGoesToSystemPromptNotBodies() — в собранном запросе есть блок available_skills c name/description, НЕТ полного тела скилла; loadSkillToolReturnsBody() — вызов load_skill(id) возвращает тело + список файлов каталога (<=10).
2) core skills: новый SkillTool (реализует контракт ToolSpec реестра AgentLoop): spec() -> декларация load_skill с параметром id; execute(id) -> тело в skill_content + список файлов.
3) Новый SkillShowcase.java: генератор строк каталога; Conversation/ChatPanel: в системную часть вместо полных тел — блок available_skills (Conversation.java:262-310 — компакция истории уже есть, дельты не делать).
4) SkillLoader.java 60-79: поле autoinvoke (ключ disable-model-invocation); SkillDefinition расширяется полем; полное тело в промпте остаётся только для явного вызова пользователем.

## Критерии приёмки
SkillShowcaseTest зелёный; в live-запросе виден только каталог, тело приходит после вызова load_skill; экономия токенов подтверждена тестом.

## Сообщение коммита
`feat: lazy skill loading through a showcase catalog`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Advertised to the model as load_skill: hands over the full instruction body
 * of one skill plus its supporting file list. Until the model calls it, only
 * the short catalog line is in the prompt.
 */
public final class SkillTool {
    /** @return tool declaration named load_skill with one string parameter id */
    public ToolSpec spec();
    /**
     * Executes one load_skill call.
     *
     * @param theId skill name from the catalog
     * @return body wrapped in skill_content plus sampled file list
     */
    public String execute(final String theId);
}
```

## Где искать информацию
- PARITY-PLAN.md -> F1; RESEARCH-extensibility-mechanics.md §1; SkillLoader.java:16-21,60-79
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
Выполнено. Коммиты: `9468fcf` (test, красный) → `c6d0154` (feat: lazy skill loading through a
showcase catalog) → `c00b02c` (fix двух опечаток в самом красном тесте). done_commit=`c6d0154`.

- `SkillShowcase.catalogSection()` — блок `## available_skills` (name: description, не более),
  скрывает скилы с `disable-model-invocation: true`, указывает модели способ забрать тело (load_skill).
- `SkillTool implements IToolExecutor` (контракт реестра AgentLoop, п.5 принципов): `load_skill` с
  параметром `id`; тело — в `<skill_content name=...>` + список файлов каталога скила (≤10, «(+N more
  file(s))»); неизвестный id → ERROR со списком доступных.
- `SkillDefinition`: новые поля sourceDir + autoInvoke (старый 4-арг. конструктор сохранён);
  `SkillLoader` читает `disable-model-invocation` из frontmatter и помнит директорию скила.
- Проводка: `ChatPanel.setSkillSupplier` — в edit-mode в tools добавляется `SkillTool`, в системную
  часть — только каталог (полных тел в промпте нет никогда); `ColleagueView.skillCatalog()` грузит
  `.colleague/skills` первого открытого проекта один раз за сессию вида.
- Отклонения: (1) карточка предполагала, что полные тела скилов уже лежат в промпте, — фактической
  проводки скилов в чате не было вообще, F1 её и построил (только agent-path: load_skill — инструмент);
  (2) в красном тесте были две собственные опечатки (подсчёт имён файлов по длине `.md`, поиск тега
  без учёта атрибута name) — исправлены отдельным test-коммитом, поведение продукта не меняли.
- Экономия токенов подтверждена тестом: каталог не содержит маркер тела, тело появляется только после
  load_skill.
