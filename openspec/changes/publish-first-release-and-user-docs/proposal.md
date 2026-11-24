# Proposal: publish-first-release-and-user-docs

## Why

Репозиторий публичный, но пользоваться им извне нельзя:

1. **README — разработческий** (структура модулей, VM-свойства, roadmap M0–M21).
   Пользователь не узнает, как поставить плагин и как им пользоваться.
2. **Релиза нет**: ни одного тега `v*`, GitHub Release пуст, GitHub Pages
   выключён (404). Релизный workflow (`release.yml`) написан, но ни разу не
   выполнялся и не может опубликовать p2-сайт, пока Pages не включён.
3. Установка «нативно легко» для EDT — это p2-сайт («Help → Install New
   Software»); он уже собирается корректно (`content.jar`, `artifacts.jar`,
   `p2.index` в `repositories/.../target/repository`), но нигде не размещён.

## What Changes

- **README.md переписывается под пользователя** (русский): что это, требования
  (1C:EDT 2026.1 (Ruby) — других версий не тестировали), установка с p2-сайта
  `https://madmar1o.github.io/colleague/` (+ zip с GitHub Release как запасной
  канал), первая настройка провайдера LLM, как пользоваться (вьюшка, контекст,
  режим правки, остановка), обновление/удаление, troubleshooting, приватность.
- **Developer-контент переезжает в docs/**: `docs/DEVELOPERS.md` (структура
  модулей, сборка из исходников, roadmap) и `docs/VM-PROPERTIES.md` (таблица
  VM-свойств).
- **release.yml дополняется**: `pages: write` permission и шаг, который при
  первом запуске включает Pages с источника `gh-pages`/root (чтобы релиз-прогон
  не падал на неактивированном Pages).
- **Контур runtime-приёмки в GitHub Actions (решено 2026-11-23, Jenkins не
  используем)**: `install-check` на каждом PR (p2 director в одноразовый
  профиль — безопасно для форк-PR, без секретов) + `live-acceptance` только на
  merge-commit `main` и nightly (деплой в **выделенный CI-экземпляр EDT**, запуск
  с мостом, `bridge_smoke` 8/8, закрытие). Деплой-скрипты переезжают из
  внерепозиторного `tools/` в репозиторий и параметризуются переменными
  (`EDT_CI_HOME` и т.п.).
- **Первый релиз v0.0.1** (решения пользователя от 2026-11-23):
  - PR A — документация + workflow;
  - PR B — версия `0.0.1` во всех pom/manifest/feature (коммит с маркером
    `[allow-version-change]` для version-freeze гейта) + запись CHANGELOG;
  - тег `v0.0.1` на merge-commit → workflow собирает и публикует релиз;
  - PR C — возврат на SNAPSHOT: `0.0.2-SNAPSHOT`.

## Out of scope

- Подпись артефактов (p2-сайт без подписи — EDT честно предупредит).
- Совместимость с другими версиями EDT, кроме 2026.1 (Ruby).
- Автообновления/маркировка «available updates» — только ручной путь установки.
- Скриншоты в README (нет актуальных изображений — следующий итерационный pass).

## Impact

- Affected specs: `user-documentation` (ADDED), `release-packaging` (MODIFIED),
  `update-channel` (ADDED), `runtime-acceptance` (ADDED).
- Affected code: `README.md`, `docs/DEVELOPERS.md`, `docs/VM-PROPERTIES.md`,
  `.github/workflows/release.yml`, `.github/workflows/ci.yml`,
  `.github/workflows/nightly.yml`, `tools/` (перенос деплой-скриптов в репо),
  версии (`pom.xml` × все модули, `feature.xml`,
  `MANIFEST.MF` × бандлы), `CHANGELOG.md`, тег `v0.0.1`, GitHub Pages (gh-pages).
