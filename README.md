# Colleague for 1C:EDT

Собственная оригинальная реализация: agent loop + skills + инструменты,
LLM — локальные модели (Ollama) и внешние провайдеры с OpenAI-совместимым API.

## Структура

```
bom/                     общий BOM (Tycho 4.0.13, EE JavaSE-17)
targets/colleague-2026.1/  целевая платформа: p2-репозиторий EDT Ruby 2026.1
bundles/
  com.mad.edt.colleague/         core: agent loop, LLM-провайдеры, skills (M2/M3)
  com.mad.edt.colleague.context/ контекст проекта для промптов (M4)
  com.mad.edt.colleague.ui.common/    общие UI-хелперы (M4)
  com.mad.edt.colleague.ui/           view/команды EDT (M4)
features/com.mad.edt.colleague.feature/
repositories/com.mad.edt.colleague.repository/   p2 update site (M5)
```

## Версии

`1.0.0.qualifier`, qualifier = `'v'yyyyMMddHHmm` — формат датированного qualifier,
принятый в плагинах 1C:EDT.

## Сборка

```
.\mvnw.cmd clean verify
```

Требования: JDK 17 (EDT 2026.1 работает на Java 17), `JAVA_HOME` должен указывать
на JDK 17 (например `C:\Program Files\Zulu\zulu-17`) — Maven берёт JDK из
`JAVA_HOME`, а не из `PATH`. Maven ставить не нужно —
Maven Wrapper скачает Maven 3.9.9. Первая сборка скачивает p2-метаданные
EDT Ruby 2026.1 (≈ несколько минут).

Результат: `repositories/com.mad.edt.colleague.repository/target/com.mad.edt.colleague.repository.zip`
— p2-репозиторий для установки в EDT (M5).

## Установка в EDT (после сборки)

Интерактивно:

```
Help → Install New Software → Add → Archive: <repository.zip>
```

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

## VM-свойства (в `1cedt.ini` после `-vmargs` или аргументами запуска)

| свойство | эффект |
|---|---|
| `colleague.llm.smoke=true` | при старте список моделей с LLM-эндпоинта в stderr |
| `colleague.open.view=true` | автооткрытие вьюшки ассистента |
| `colleague.ui.selftest=true` | тестовый вопрос с приложенным контекстом, ответ в stderr |

## roadmap

| этап | содержание | статус |
|---|---|---|
| M0 | git + конвенции | готово |
| M1 | Tycho-scaffold собирается (`mvnw verify` SUCCESS) | готово |
| M2 | LLM-слой: Ollama + OpenAI-совместимые провайдеры | готово |
| M3 | agent loop + skills (SKILL.md, tools JSON) | готово |
| M4 | EDT UI: view, команды, контекст проекта | готово |
| M5 | p2 install + проверка на workspace ERPUH_DEMO | готово |
| M6.1–M6.2 | чат-мессенджер: контакты + вкладки чатов | готово |
| M6.3 | диалог подключения провайдера (Preferences + SecureStorage) | план |
