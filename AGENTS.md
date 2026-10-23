# AGENTS.md — правила работы в этом репозитории

## Проект
- Плагин 1C:EDT (Java, Eclipse RCP/OSGi, Tycho), ID `com.mad.edt.colleague.*`.
- Целевая EDT: 2026.1 (p2 `https://edt.1c.ru/downloads/releases/ruby/2026.1/`), EE JavaSE-17.
- Архитектурный эталон — 1C:Workmate 1.0.8 (`com.e1c.edt.ai`), исследования — в Qdrant `colleague_facts`.

## Конвенции
- Не менять `Bundle-Version` (`x.y.z.qualifier`) и qualifier-формат `'v'yyyyMMddHHmm`.
- Новый бандл = MANIFEST.MF + build.properties + plugin.properties + pom.xml + запись в `bundles/pom.xml`, feature.xml и (если надо) category.xml.
- Заголовки MANIFEST в том же порядке, что в существующих бандлах.
- Пакеты: `com.mad.edt.colleague.<область>`. Публичный API — в `Export-Package`, остальное `x-internal:=true`.
- Require-Bundle/Import-Package — с версиями-диапазонами; версии сверять с content.xml целевого p2-репозитория, не выдумывать.
- Коммиты: `<type>: <описание>` (chore|feat|fix|docs|refactor|test), ветка `main`.
- Секреты (API-ключи провайдеров) не коммитить; конфиг LLM — Preferences/Secure storage.

## Проверки
- Перед пушем: `.\mvnw.cmd clean verify` обязан проходить.
- Изменения MANIFEST/feature — пересобирать, не «на глаз».

## Запреты
- Не менять `targets/*.target` без веской причины (это контракт целевой платформы).
- Не обновлять Tycho/EDT-версии «за компанию» с основной задачей.
