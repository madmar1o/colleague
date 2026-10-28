# PP-F0 — Общий frontmatter-загрузчик и reload-реестр (DRY-фундамент)

Веха: M23 | Ветка: `feature/pp-f0-frontmatter-parser` | Борд: Qdrant `colleague_tasks`, payload.id=`PP-F0`
Зависимости: нет; начинать после слияния PP-P1..P7 в main (порядок вех)

## Статус и порядок работы (для каждого агента)
1. Начать с `git log --oneline main..HEAD` и `git diff main...HEAD --stat` — что уже сделано в ветке.
2. Взять Qdrant-точку задачи (curl POST http://localhost:6333/collections/colleague_tasks/points/scroll
   с фильтром `match` по payload.id = PP-F0) — поля status/details/acceptance/done_commit.
3. Выполнять шаги атомарно: один логический шаг = один коммит; каждый коммит — зелёный `clean verify`.
4. После приёмки: status -> done, вписать done_commit (хеш) в Qdrant-точку, ветку слить в
   milestone-ветку вехи M23.

## Что сделать (пошагово)
1) Красный тест FrontmatterParserTest: parsesKeyColonValueWithColonInValue() (description: use: carefully -> значение с двоеточием), ignoresBomAndCrlf().
2) Новый пакет com.mad.edt.colleague.ext (core): Frontmatter.java (split/fields/body) и ReloadableRegistry.java (reload/get/snapshot; при сбое загрузки прежний снимок остаётся).
3) Рефакторинг SkillLoader.loadOne поверх Frontmatter — поведение прежнее; существующие тесты SkillLoaderTest НЕ менять (они защита рефакторинга).

## Критерии приёмки
FrontmatterParserTest и SkillLoaderTest зелёные; в core нет дублирующих парсеров; чужих названий в коде нет.

## Сообщение коммита
`refactor: one frontmatter parser behind skills, commands and profiles`

## Javadoc-каркас (перенести в код 1-в-1, английский)
```java
/**
 * Parses a --- delimited key:value header out of a markdown file and
 * returns the header fields plus the untouched body. Shared by skills,
 * commands and agent profiles so each format stays a thin reader over one parser.
 */
public final class Frontmatter {
    /**
     * Splits the raw file text into header fields and body.
     *
     * @param theRaw full file text, CRLF and BOM tolerated
     * @return parsed header fields and the body after the closing fence
     */
    public static Frontmatter split(final String theRaw);
    /** @return unmodifiable map of header keys to trimmed values */
    public Map<String, String> fields();
    /** @return the body below the header, stripped */
    public String body();
}

/**
 * A name-keyed catalog that can be rebuilt from a source without losing
 * registration order, and can restore the previous snapshot when a rebuild
 * fails. One mechanism behind skills, commands and agent profiles.
 */
public final class ReloadableRegistry<V> {
    /**
     * Replaces the whole catalog with a freshly loaded snapshot.
     *
     * @param theSnapshot new contents; on any load failure the previous
     *                    snapshot stays in place
     */
    public void reload(final Map<String, V> theSnapshot);
    public V get(String theName);
    public Map<String, V> snapshot();
}
```

## Где искать информацию
- PARITY-PLAN.md -> F0; текущий парсер — SkillLoader.java:53-82
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
Выполнено. Коммиты: `6de2cbb` (test, красный) → `227a587` (test, защита рефакторинга) →
`a841e0d` (refactor: one frontmatter parser behind skills, commands and profiles). done_commit=`a841e0d`.

- Новый пакет `com.mad.edt.colleague.ext` (core, Export-Package): `Frontmatter` (BOM/CRLF-толерантный
  split, key по первому двоеточию, value с двоеточиями сохраняется, body без заголовка — весь текст) и
  `ReloadableRegistry<V>` (reload(Map), reload(SnapshotBuilder) — бросивший билдер оставляет прежний
  снимок live, get/snapshot, порядок регистрации через LinkedHashMap).
- `SkillLoader.loadOne` теперь тонкий читатель поверх `Frontmatter` (28 строк парсинга → 6).
- Отклонение от карточки: «существующий SkillLoaderTest» в репозитории отсутствовал — рефакторинг был
  без защиты, поэтому `SkillLoaderTest` создан (4 теста: header/colons, fallback на имя директории,
  tools.json, сортировка и пропуск директорий без SKILL.md) и закоммичен до refactor-коммита.
- Красный шаг: `FrontmatterParserTest` (6 тестов) закоммичен до существования пакета ext.
