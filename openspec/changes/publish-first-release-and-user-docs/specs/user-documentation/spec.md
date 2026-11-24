# Spec delta: user-documentation

## ADDED Requirements

### Requirement: README teaches installation end-to-end

The repository `README.md` SHALL contain a user-facing installation guide that
works without reading any other file: the p2 update site URL, the exact EDT
menu path, the unsigned-content warning, and the zip-archive fallback from
GitHub Releases.

#### Scenario: p2 site install from README

- **WHEN** a user follows README steps in 1C:EDT 2026.1 (Ruby)
- **THEN** «Help → Install New Software → Add» with location
  `https://madmar1o.github.io/colleague/` lists the Colleague feature
- **AND** the install completes and the view «Colleague Assistant» becomes
  available after restart

#### Scenario: zip fallback from README

- **WHEN** the user follows the README fallback path with the release zip
- **THEN** «Add → Archive» with that zip lists the same feature

### Requirement: README states environment requirements honestly

The README SHALL state that only 1C:EDT 2026.1 (Ruby) has been tested, that an
LLM provider (local Ollama or an OpenAI-compatible endpoint) is required, and
that the semantic layer additionally requires Qdrant while chat and project
search work without it.

#### Scenario: no Qdrant mentioned as a hard requirement

- **WHEN** a reader scans the requirements section
- **THEN** Qdrant appears only under the optional semantic layer

### Requirement: README covers first-run configuration and daily usage

The README SHALL explain provider setup via the «Провайдеры» page and the core
usage loop: ask in the view, «Отправить в Colleague» / «Объяснить выделение»
from the editor context menu, edit mode with per-change confirmation, ESC stops
a run.

#### Scenario: first question after install

- **WHEN** a new user follows README first-run steps
- **THEN** they can pick a provider, type a question, and get an answer without
  consulting any other document

### Requirement: Developer content lives outside README

Roadmap, module structure, build-from-sources instructions and the VM-properties
table SHALL live in `docs/DEVELOPERS.md` and `docs/VM-PROPERTIES.md`; README
links to them with relative repository paths only.

#### Scenario: sanitize gate stays green

- **WHEN** the hygiene job runs the sanitize gate over the new docs
- **THEN** no machine-specific absolute path or third-party product name is found
