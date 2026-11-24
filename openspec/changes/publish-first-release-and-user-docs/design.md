# Design: publish-first-release-and-user-docs

## D1. Канал установки — p2-сайт на GitHub Pages (решено)

- `peaceiris/actions-gh-pages@v4` публикует **содержимое** `repositories/target/repository`
  в корень ветки `gh-pages` → адрес сайта: `https://madmar1o.github.io/colleague/`.
- Пользователь: `Help → Install New Software → Add → Location = URL сайта`;
  EDT сам подтянет `content.jar`/`artifacts.jar`. Zip с Release — запасной канал
  (`Add → Archive`), тот же p2-содержимый.
- Сайт всегда содержит **последний** релиз; фиксированная копия каждого тега
  лежит ассетом в GitHub Release. Это осознанно: simple first, версионный
  каталог (`/releases/<tag>/`) — будущая итерация, не сейчас.

## D2. Включение Pages — терпимый first-run

`peaceiris/actions-gh-pages@v4` умеет сам включить Pages (вызывает REST
`POST /repos/…/pages` при первом пуше gh-pages), но требует права `pages: write`
у `GITHUB_TOKEN`. В release.yml добавляется:

```yaml
permissions:
  contents: write
  pages: write
```

Если по какой-то причине Pages не поднялся автоматически, одноразово вручную:
`gh api repos/{owner}/{repo}/pages -X POST -f "name=Colleague p2 update site" -f "source[branch]=gh-pages" -f "source[path]=/"`.
Workflow от этого не ломается: публикация на gh-pages — это git-пуш, Pages-сервер
поднимает сайт асинхронно.

## D3. Версионирование первого релиза (решено: v0.0.1)

- OSGi/EDT требует числовые `MAJOR.MINOR.MICRO`; датный qualifier уже встроен:
  `0.0.1.qualifier` → `0.0.1.vГГГГММДДччмм` при сборке релиза.
- Порядок: merge PR B (версия `0.0.1`, коммит `chore: prepare release 0.0.1 [allow-version-change]`)
  → тег `v0.0.1` на merge-commit → `release.yml` по тегу.
- Version-freeze гейт: PR B меняет `Bundle-Version` — пропускается маркером в
  subject коммита `[allow-version-change]` (гейт это поддерживает).
- После релиза PR C: `0.0.1 → 0.0.2-SNAPSHOT` (тот же маркер не нужен —
  SNAPSHOT-строка не меняет `Bundle-Version`-заморозку? Меняет — маркер нужен
  и в PR C; фиксируем это в задачах).

## D4. Граница README

README (пользовательский, RU): что это / требования / установка (p2 URL, zip,
предупреждение о неподписанном контенте) / первая настройка (Ollama или
OpenAI-совместимый API, страница «Провайдеры») / как пользоваться (вьюшка
«Colleague Assistant», контекстное меню «Объяснить выделение», «Сгенерировать
метод», режим правки с подтверждением, ESC = стоп) / обновление и удаление /
troubleshooting / приватность (локальные модели — данные не уходят наружу;
внешний API — по ключу пользователя).

Всё разработческое — в `docs/DEVELOPERS.md` (структура, сборка `mvnw clean
verify`, e2e-стенд, roadmap M0–M21) и `docs/VM-PROPERTIES.md` (диагностические
VM-свойства). Ссылки из README на них — относительные, без машинных путей
(sanitize-гейт).

## D6. Контур runtime-приёмки — только GitHub Actions (решено, Jenkins не используем)

- **`install-check` (ci.yml, каждый PR, нужен до мержа)**: `mvnw package` уже
  сделан в build-test; job берёт `repositories/target/repository` (clean:false)
  и гоняет p2 director (`org.eclipse.equinox.p2.director`) в одноразовый
  профиль-выход (`-destination out/ci-profile -profile ci`) от JDK раннера.
  Секретов не требует → безвреден для форк-PR; становится required-чеком.
  Экземпляр EDT для launcher/director берётся из `EDT_CI_HOME` (runner .env).
- **`live-acceptance` (ci.yml, только `push` на main + workflow_dispatch;
  nightly.yml — по расписанию)**: это тот же ручной цикл, что сейчас
  (deploy → launch bridge → smoke → close), но на **выделенном CI-экземпляре
  EDT**: `EDT_CI_HOME` — отдельная копия EDT 2026.1 на машине CI (не рабочий
  экземпляр разработчика!), `EDT_CI_PROFILE` — отдельный workspace.
  Шаги: deploy-скрипт в CI-экземпляр → запуск с мостом (порт из env, по
  умолчанию 8791 — не конкурирует с ручным 8790) → ждать маркер активации в
  `.metadata\.log` CI-профиля → `bridge_smoke.ps1 -Port …` = 8/8 → закрыть
  экземпляр → артефакты (отчёт smoke, хвост лога) в артефакты ран.
  На форк-PR job не появляется (не триггерится от pull_request).
- **Скрипты переезжают в репозиторий** (`tools/acceptance/`):
  `colleague_deploy.ps1`, `colleague_launch_bridge.ps1`, `bridge_smoke.ps1`,
  `test_path_guard.*` — с параметрами `-EdtHome -Profile -Port` вместо
  захардкоженных путей; sanitize-гейт после переноса обязан остаться зелёным
  (машинных путей в них быть не должно — только env).
- **Конкурентность**: `concurrency: live-acceptance` (cancel-in-progress=false) —
  merge-запуски сериализуются; nightly и post-merge не делят CI-экземпляр EDT.
- Jenkins-контейнеры остаются стоять (могут принадлежать другим проектам),
  в контуре Colleague не участвуют; решение по их отключению — за владельцем.

## D5. Требования к среде пользователя (что пишем честно)

- 1C:EDT **2026.1 (Ruby)** — единственная тестировавшаяся версия.
- LLM: локальный Ollama **или** любой OpenAI-совместимый эндпоинт с ключом.
- Семантический индекс — опционально: нужен Qdrant + эмбеддинг-эндпоинт
  (`-Dcolleague.semantic.store=…`); без него чат и поиск по проекту работают.
