# Colleague for 1C:EDT

AI-ассистент-плагин для 1C:EDT 2026.1 по архитектуре 1C:Workmate 1.0.8
(`com.e1c.edt.ai`): agent loop + skills + инструменты, LLM — локальные модели
(Ollama) и внешние провайдеры в стиле OpenCode.

## Структура

```
bom/                     общий BOM (Tycho 4.0.13, EE JavaSE-17)
targets/colleague-2026.1/  целевая платформа: p2-репозиторий EDT Ruby 2026.1
bundles/
  com.mad.edt.colleague/         core: agent loop, LLM-провайдеры, skills (M2/M3)
  com.mad.edt.colleague.context/ контекст проекта для промптов (M3)
  com.mad.edt.colleague.ui.common/    общие UI-хелперы (M4)
  com.mad.edt.colleague.ui/           view/команды EDT (M4)
features/com.mad.edt.colleague.feature/
repositories/com.mad.edt.colleague.repository/   p2 update site (M5)
```

## Версии

`1.0.0.qualifier`, qualifier = `'v'yyyyMMddHHmm` (как Workmate `1.0.8.v202609241142`).

## Сборка

```
.\mvnw.cmd clean verify
```

Требования: JDK 17 (EDT 2026.1 работает на Java 17). Maven ставить не нужно —
Maven Wrapper скачает Maven 3.9.9. Первая сборка скачивает p2-метаданные
EDT Ruby 2026.1 (≈ несколько минут).

Результат: `repositories/com.mad.edt.colleague.repository/target/com.mad.edt.colleague.repository.zip`
— p2-репозиторий для установки в EDT (M5).

## Установка в EDT (после сборки)

```
Help → Install New Software → Add → archive: <repository.zip>
```

## roadmap

| этап | содержание |
|---|---|
| M0 | git + конвенции |
| M1 | Tycho-scaffold собирается (`mvnw verify` SUCCESS) |
| M2 | LLM-слой: Ollama + OpenAI-совместимые провайдеры |
| M3 | agent loop + skills (SKILL.md, tools JSON) |
| M4 | EDT UI: view, команды, контекст проекта |
| M5 | p2 install + проверка на workspace ERPUH_DEMO |
