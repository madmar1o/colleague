# Colleague for 1C:EDT

Собственная оригинальная реализация: agent loop + skills + инструменты,
LLM — локальные модели (Ollama) и внешние провайдеры с OpenAI-совместимым API.

## Структура

```
bom/                     общий BOM (Tycho 4.0.13, EE JavaSE-17)
targets/colleague-2026.1/  целевая платформа: p2-репозиторий EDT Ruby 2026.1
bundles/
  com.mad.edt.colleague/         core: agent loop, LLM-провайдеры, skills,
                                 диалоговая память (M2/M3/M9)
  com.mad.edt.colleague.context/ контекст проекта: поиск + семантический
                                 индекс модулей (M4/M8/M10)
  com.mad.edt.colleague.ui.common/    общие UI-хелперы (M4)
  com.mad.edt.colleague.ui/           view/команды EDT, страницы-HTML (M4/M9)
  *.tests/                            тестовые фрагменты: JUnit 5 в реакторе (M10.5)
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
| `colleague.search.selftest=true` | самотест поиска по индексу проекта в stderr |
| `colleague.browser.selftest=true` | сквозная проверка моста страниц (жест отправки прямо со страницы) |
| `colleague.browser.abortproof=true` | автономная проверка остановки хода внутри самотеста браузера |
| `colleague.semantic.store=<url>` | адрес векторного хранилища, включает семантический слой |
| `colleague.semantic.model=<имя>` | эмбеддинг-модель для семантического индекса |
| `colleague.semantic.embed=<url>` | эндпоинт эмбеддингов |

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
| M6.3 | диалог подключения провайдера + сохранение в preferences | готово |
| M6.5 | мобильный режим компоновки без перезапуска | готово |
| M7 | режим правки (два ESC) + инструменты проекта с подтверждением записи | готово |
| M8 | поиск контекста проекта: индекс + сниппеты + автоприкрепление | готово (база) |
| M7.5 | стиль чата: пузыри сообщений, адаптивные цвета (в fallback-режиме) | готово |
| M9 | страничный интерфейс: HTML-страницы в браузере, мост, отказ от десктопа | готово |
| M9.5 | прерывание хода (ESC = стоп), Markdown-ответы | готово |
| M10 | семантический индекс модулей: векторное хранилище + гибридный контекст | готово |
| M10.5 | юнит-тесты в реакторе: JUnit 5, контрактные тесты | готово |
| M11 | прогрев рантайма (keep_alive), контекстное окно по размеру промпта, живучесть семантического прохода | готово |
| M12 | страница «Провайдеры»: список, смена провайдера по умолчанию, добавить/изменить/удалить | готово |
| M13+ | правки по диффам, полировка UI, наблюдение за полным прогоном семантики | план |

Поведение чата, влияющее на отклик (M11): локальный рантайм держит модель
в памяти (`keep_alive`), первый вопрос прогретого сеанса не платит за
холодную загрузку; размер контекстного окна (`options.num_ctx`)
подбирается по длине промпта — раньше рантайм молча обрезал длинный
промпт до своего крошечного дефолта. Провайдеры настраиваются на
странице «Провайдеры» (шестерёнка): сменить провайдера по умолчанию,
изменить адрес/ключ, добавить новый эндпоинт с проверкой соединения;
сохранённый ключ наружу не показывается.
