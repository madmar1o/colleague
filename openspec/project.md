# Project context — Colleague for 1C:EDT

- Плагин 1C:EDT (Java 17, Tycho 4.0.13, целевая платформа EDT 2026.1 Ruby).
- Модули: `bundles/com.mad.edt.colleague` (core), `.context` (поиск/семантика),
  `.ui` (view/команды), тестовые фрагменты `*.tests`;
  `features/com.mad.edt.colleague.feature`; `repositories/...repository` (p2 update site).
- Координаты: `1.0.0-SNAPSHOT` в pom'ах, `Bundle-Version: 1.0.0.qualifier`,
  qualifier = `v'yyyyMMddHHmm'` (датный формат плагинов 1C:EDT).
- CI: GitHub Actions, self-hosted runner `colleague-win`; гейты `hygiene`
  (коммит-подjectы, sanitize, Javadoc, version-freeze, канон-кейсы), `build-test`,
  `sonar-gate`. `main` заперт branch protection; слияние только через PR.
- Релизный workflow: тег `v*` → build → zip p2-сайта → SHA256SUMS → notes из
  conventional commits → GitHub Release + публикация p2-сайта на gh-pages.
- Конвенции: TDD red-first, Javadoc на новый public API, коммиты
  `feat|fix|test|refactor|docs|chore|ci|build:`, в репозитории нет машинных
  абсолютных путей и имён чужих AI-продуктов (sanitize-гейт).
