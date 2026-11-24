# Colleague — разработчикам

Пользовательская документация — в [README.md](../README.md). Здесь — сборка,
структура, CI и roadmap.

## Структура

```
bom/                       общий BOM (Tycho, EE JavaSE-17)
targets/colleague-2026.1/  целевая платформа: p2-репозиторий EDT Ruby 2026.1
bundles/
  com.mad.edt.colleague/          core: agent loop, LLM-провайдеры, skills,
                                  диалоговая память
  com.mad.edt.colleague.context/  контекст проекта: поиск + семантический
                                  индекс модулей
  com.mad.edt.colleague.ui/       view/команды EDT, страницы-HTML
  *.tests/                        тестовые фрагменты: JUnit 5 в реакторе
features/com.mad.edt.colleague.feature/
repositories/com.mad.edt.colleague.repository/   p2 update site
tools/ci/                 гейты и обвязка CI
tools/acceptance/         приёмные скрипты (деплой в EDT, мост, smoke)
docs/                     документация (эта, CI, карточки задач)
openspec/                 предложения изменений и спецификации возможностей
```

## Версии

Координаты релиза: `MAJOR.MINOR.PATCH` (+ `qualifier` вида `vГГГГММДДччмм` —
датированный qualifier, принятый в плагинах 1C:EDT). Ствол носит `*-SNAPSHOT`;
релиз — коммит с явными версиями и тегом `vMAJOR.MINOR.PATCH` (см.
`docs/ci/SETUP-GITHUB.md`). Случайные правки `Bundle-Version`, `targets/` и
версий Tycho останавливает гейт `tools/ci/version_freeze_check.ps1` —
легитимная смена версии требует маркера `[allow-version-change]` в заголовке
коммита.

## Сборка

```
.\mvnw.cmd clean verify
```

Требования: JDK 17 в `JAVA_HOME` (Maven берёт JDK из `JAVA_HOME`, не из `PATH`).
Maven ставить не нужно — Maven Wrapper скачает его сам. Первая сборка скачивает
p2-метаданные EDT Ruby 2026.1 (≈ несколько минут).

Результат: p2-репозиторий в
`repositories/com.mad.edt.colleague.repository/target/repository`
(и архив `*.repository.zip` рядом) — установка в EDT по этому сайту.

### e2e-стенд одного агентского хода

Переменная окружения `COLLEAGUE_E2E=1` (плюс `COLLEAGUE_E2E_MODEL=<модель>`)
перед `clean verify` дополняет прогон одним реальным агентским ходом поверх
живой локальной модели; без переменной стенд молча пропускается, обычный
прогон не требует сети.

## Установка собранного в EDT

Интерактивно: Help → Install New Software → Add → Location: путь к
`target/repository` (или Archive: `*.repository.zip`).

Без UI (p2 director, тот же результат; site — каталог `target/repository`,
не zip):

```
java -jar <edt>\plugins\org.eclipse.equinox.launcher_*.jar -nosplash -consoleLog \
  -application org.eclipse.equinox.p2.director \
  -repository file:/<путь>/colleague/repositories/com.mad.edt.colleague.repository/target/repository/ \
  -installIU com.mad.edt.colleague.feature.feature.group \
  -destination <edt> -profile <профиль EDT>
```

Обновление — сначала `-uninstallIU` того же iu, затем `-installIU`.
Для установки в пустой профиль добавьте второй `-repository file:/.../.p2/pool`
(транзитивные зависимости Eclipse там же, где их уже скачал EDT).

## CI

Ветка → PR в `main` → зелёные обязательные проверки → мерж `gh pr merge
--merge` (замок `main`, см. `docs/ci/SETUP-GITHUB.md`). Проверки:

| job | что делает |
|---|---|
| `hygiene` | заголовки коммитов, Javadoc на новый публичный API, замороженные координаты сборки, машинные пути/чужие имена (sanitize), канон-кейсы гейтов |
| `build-test` | `mvnw clean verify` (юнит-тесты в реакторе) |
| `sonar-gate` | анализ в SonarQube + quality gate (coverage ≥ 60) |
| `install-check` | деплой собранного feature в scratch-копию CI-экземпляра EDT (без запуска); проверка, что пропатченный `bundles.info` и пул согласованы |
| `live-acceptance` | (merge в main / dispatch / nightly) деплой в выделенный CI-экземпляр EDT, запуск с веб-мостом, `bridge_smoke` 8/8 |

Репозиторий публичный: в отслеживаемых файлах запрещены машинные абсолютные
пути и токены (гейт `tools/ci/sanitize_check.ps1`) — конфигурация машины живёт
в `.env` раннера:

| переменная | назначение |
|---|---|
| `JAVA_HOME_17` | JDK 17 для сборки и p2-инструмента |
| `SONAR_HOST_URL` | SonarQube, доступный раннеру |
| `EDT_CI_HOME` | корень выделенного CI-экземпляра EDT (вне репозитория, на той же глубине от `%USERPROFILE%`, что и рабочая установка: относительные пути `bundles.info` к общему пулу `.p2` работают без правок) |
| `EDT_CI_DEV_HOME` | корень рабочей установки EDT (перештамповывается тем же штампом, что и CI: общий пул `.p2`) |
| `EDT_CI_PROFILE` | workspace-каталог CI-экземпляра (`-data`) |
| `EDT_CI_PORT` | порт веб-моста CI (по умолчанию 8791; ручной мост — 8790) |
| `EDT_CI_POOL` | каталог `plugins` общего p2-пула (по умолчанию `%USERPROFILE%\.p2\pool\plugins`) |

### Приёмочный контур (`tools/acceptance/`)

EDT-установка ставится как платформа, feature в её p2-профиле не состоит,
поэтому транспорт проверенный: джары с штампом `vГГГГММДДччмм` в общий пул
и патч `bundles.info` установки (`edt_deploy_feature.ps1`). Инвариант общего
пула: все установки, ссылающиеся на пул, перештамповываются одним прогоном,
а cleanup удаляет только те джары, на которые не ссылается ни одна
найденная установка (реестр `.p2/profiles.info`, дерево `%USERPROFILE%\CI_EDT`,
цели деплоя). `install-check` деплоит в одноразовую копию (`-ScratchCopy`) и
проверяет ссылки `bundles.info` на файлы пула; `live-acceptance` добавляет
запуск (`edt_launch_ci.ps1`), ожидание маркера активации
(`edt_wait_activated.ps1`), 8 самопроверок моста (`bridge_smoke.ps1`) и
остановку (`edt_stop_ci.ps1`).

## Релизы

Тег `v*` → workflow `release.yml`: сборка, GitHub Release (архив p2-сайта +
SHA256SUMS), публикация p2-сайта на GitHub Pages (`main`-ветка `gh-pages`) —
публичный URL сайта указан в README. После релиза ствол возвращается на
`*-SNAPSHOT` следующей версии.

## Roadmap

| этап | содержание | статус |
|---|---|---|
| M0 | git + конвенции | готово |
| M1 | Tycho-scaffold собирается (`mvnw verify` SUCCESS) | готово |
| M2 | LLM-слой: Ollama + OpenAI-совместимые провайдеры | готово |
| M3 | agent loop + skills (SKILL.md, tools JSON) | готово |
| M4 | EDT UI: view, команды, контекст проекта | готово |
| M5 | p2 install + проверка на рабочем пространстве | готово |
| M6.1–M6.2 | чат-мессенджер: контакты + вкладки чатов | готово |
| M6.3 | диалог подключения провайдера + сохранение в preferences | готово |
| M6.5 | мобильный режим компоновки без перезапуска | готово |
| M7 | режим правки (два ESC) + инструменты проекта с подтверждением записи | готово |
| M7.5 | стиль чата: пузыри сообщений, адаптивные цвета | готово |
| M8 | поиск контекста проекта: индекс + сниппеты + автоприкрепление | готово (база) |
| M9 | страничный интерфейс: HTML-страницы в браузере, мост | готово |
| M9.5 | прерывание хода (ESC = стоп), Markdown-ответы | готово |
| M10 | семантический индекс модулей: векторное хранилище + гибридный контекст | готово |
| M10.5 | юнит-тесты в реакторе: JUnit 5, контрактные тесты | готово |
| M11 | прогрев рантайма (keep_alive), контекстное окно по размеру промпта | готово |
| M12 | страница «Провайдеры»: список, дефолт, добавить/изменить/удалить | готово |
| M13 | правки по диффам: движок блоков поиск/замена, окно диффа, `edit_file` | готово |
| M14 | устойчивость шагов: бюджет вывода, повтор round-trip, стрим раундов | готово |
| M15 | трасса шагов агента в консоли + самопочинка пустого финального раунда | готово |
| M16 | бюджет контекста хода с вырезкой из середины и отметкой разрыва | готово |
| M17 | гейт генератора: живой ход держит генератор, фоновый проход ждёт | готово |
| M18 | параллельные read-only вызовы инструментов внутри раунда | готово |
| M19 | e2e-стенд одного агентского хода + живой зонд агента в UI | готово |
| M20 | видимый план шагов (`update_plan`), память уроков (`remember`), дайджест длинного диалога | готово |
| M21–M27 | спеки 0002–0019: инструменты-контракты, хук-реестр, open-in-editor, открытые расширения | готово |
| M28+ | PP-G1 (стабильное имя коллекции индекса), полировка UI | план |

Открытые задачи — борд `PP-*` (см. `docs/taskcards/INDEX.md`).
