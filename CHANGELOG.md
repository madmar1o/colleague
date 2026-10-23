# Changelog

## [Unreleased]
### M0/M1 — scaffold
- Tycho-иерархия: bom, parent, targets/bundles/features/repositories.
- Target platform: EDT Ruby 2026.1 p2.
- 4 бандла, feature, p2 repository.

### M2 — LLM-слой (core)
- `com.mad.edt.colleague.llm`: `ILLMProvider`, `ChatRequest`/`ChatMessage`/`ChatResult`,
  `ProviderConfig`, `LLMProviderFactory`, `LLMSettings`.
- Клиенты: `OllamaLLMProvider` (`/api/chat`, `/api/tags`, `/api/version`),
  `OpenAiCompatibleLLMProvider` (`/chat/completions`, `/models`).
- Транспорт `java.net.http.HttpClient`, JSON — Gson из target platform.
- Дымовой хук активации в ui-бандле, сетевой smoke по VM-свойству
  `colleague.llm.smoke`.

### M3 — agent loop + skills (core)
- `com.mad.edt.colleague.agent`: `AgentLoop` (ReAct: model -> tool call ->
  результат -> повтор), `IToolExecutor`, `AgentAnswer`.
- `com.mad.edt.colleague.skills`: `SkillLoader` (каталог `<name>/SKILL.md` с
  YAML-шапкой + optional `tools.json`), `SkillDefinition`, `SkillRegistry`
  (рендер секции системного промпта).
- LLM-слой расширен нативным tool calling: `ToolSpec`, `ToolCall`,
  tool-роль сообщений, `ChatResult.getToolCalls`.
