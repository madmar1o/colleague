# Tasks: publish-first-release-and-user-docs

Порядок линейный; каждый PR — зелёные `hygiene`/`build-test`/`sonar-gate`
перед мержем (`main` заперт, только PR).

## PR A — документация + workflow (ничего не релизим)

- [x] A1. `docs/VM-PROPERTIES.md`: перенести таблицу VM-свойств из README как есть,
      добавить шапку «для диагностики, обычному пользователю не нужно».
- [x] A2. `docs/DEVELOPERS.md`: структура модулей, сборка из исходников
      (`mvnw clean verify`, JDK 17 через `JAVA_HOME`), e2e-стенд `COLLEAGUE_E2E`,
      roadmap M0–M21 — перенести из README.
- [x] A3. Переписать `README.md` под пользователя (RU): что это; требования
      (EDT 2026.1 (Ruby), Ollama/OpenAI-совместимый API, Qdrant — только
      семантический слой); установка с `https://madmar1o.github.io/colleague/`
      (пошапно, с предупреждением о неподписанном контенте); запасной канал —
      zip с Release; первая настройка провайдера; как пользоваться (вьюшка,
      контекстное меню, режим правки, ESC=стоп); обновление/удаление;
      troubleshooting; приватность; ссылки на A1/A2 и CHANGELOG относительными
      путями.
- [x] A4. `.github/workflows/release.yml`: добавить `pages: write` в `permissions`;
      убедиться, что шаг gh-pages идёт после создания релиза.
- [x] A5. Перенос приёмных скриптов в репозиторий `tools/acceptance/`
      (`colleague_deploy.ps1`, `colleague_launch_bridge.ps1`, `bridge_smoke.ps1`,
      `test_path_guard.ps1|.mjs`) с параметрами `-EdtHome -Profile -Port` и
      чтением `EDT_CI_HOME`/`EDT_CI_PROFILE` из env; в раннер `.env` добавить
      `EDT_CI_HOME`, `EDT_CI_PROFILE` (машинные пути живут только в .env раннера).
 - [x] A6. `ci.yml`: job `install-check` (обязательный до мержа) — p2 director в
      одноразовый профиль из `repositories/target/repository` (clean:false),
      без секретов; добавить его в required-чеки branch protection (gh api) — после мержа PR A.
- [x] A7. `ci.yml`: job `live-acceptance` (только push на main + dispatch,
      `concurrency: live-acceptance`) — деплой в CI-экземпляр EDT → запуск с
      мостом → маркер активации → `bridge_smoke` 8/8 → закрытие → артефакты.
      `nightly.yml`: тот же шаг по расписанию + issue с отчётом при провале.
- [x] A8. Локальная проверка: `tools/ci/sanitize_check.ps1` (PASS),
      `tools/ci/javadoc_check.ps1` (PASS — кода нет), битых относительных ссылок
      в README/docs нет (`out/`-скрипт или ручной проход по ссылкам);
      ручной прогон перенесённых скриптов на CI-экземпляре EDT (8/8).
- [ ] A9. PR в `main`, мерж после зелёного CI (первый же merge прогонит
      `live-acceptance` — это и есть его обкатка).

## PR B — подготовка версии 0.0.1

- [ ] B1. Красный/shell-проверка: `git grep -l "1.0.0-SNAPSHOT"` перечисляет все
      pom-файли; после правки — ноль совпадений.
- [ ] B2. `0.0.1` во всех `<version>` pom (корень, bom, targets, bundles,
      features, repositories), `Bundle-Version: 0.0.1.qualifier` во всех
      MANIFEST.MF, `version="0.0.1.qualifier"` в feature.xml и repository-артефактах
      (category/build.properties не трогаем).
- [ ] B3. `CHANGELOG.md`: секция `## [0.0.1] — 2026-11-23` (первый публичный релиз,
      что умеет).
- [ ] B4. Локально: `$env:JAVA_HOME=$env:JAVA_HOME_17; .\mvnw.cmd -B clean verify`
      зелёный; `repositories/.../target/repository` содержит `0.0.1.v*` артефакты,
      без SNAPSHOT.
- [ ] B5. Коммит `chore: prepare release 0.0.1 [allow-version-change]`
      (маркер обязателен — version-freeze гейт блокирует правку Bundle-Version);
      PR, мерж после зелёного CI.

## Релиз v0.0.1 (не PR)

- [ ] C1. На merge-commit `main`: `git tag v0.0.1 && git push origin v0.0.1`.
- [ ] C2. Дождаться `release.yml`: GitHub Release `Colleague v0.0.1` с ассетами
      `colleague-update-site-v0.0.1.zip`, `SHA256SUMS`; notes по conventional commits.
- [ ] C3. Проверить Pages: `Invoke-WebRequest https://madmar1o.github.io/colleague/p2.index`
      → 200 (первый пуш gh-pages включает Pages сам; если нет — разово
      `gh api repos/madmar1o/colleague/pages -X POST -f "source[branch]=gh-pages" -f "source[path]=/"`).
- [ ] C4. Живая приёмка «нативно легко»: чистый EDT 2026.1 → Install New Software →
      URL-сайта → установить → перезапуск → маркер активации в `.metadata\.log` →
      `tools/acceptance/bridge_smoke.ps1` 8/8 → удалить через About → Installation Details.
- [ ] C5. Проверить SHA256SUMS ассета (`Get-FileHash` vs файл релиза).

## PR C — возврат ствола на SNAPSHOT

- [ ] D1. `0.0.1 → 0.0.2-SNAPSHOT` во всех pom; `Bundle-Version: 0.0.2.qualifier`;
      коммит `chore: back to 0.0.2-SNAPSHOT [allow-version-change]`.
- [ ] D2. PR, мерж после зелёного CI; `git grep 0.0.1-SNAPSHOT` пустой.

## Final

- [ ] E1. Обновить `docs/ci/SETUP-GITHUB.md` (Pages активен, релизный путь, контур
      runtime-приёмки и требуемые чек-имена `hygiene`/`build-test`/`sonar-gate`/
      `install-check`),
      `notes/state.md` — точка возобновления после релиза.
- [ ] E2. Закрыть change: перенести дельты в `openspec/specs/` (archive по
      openspec-конвенции, если CLI недоступен — вручную).
