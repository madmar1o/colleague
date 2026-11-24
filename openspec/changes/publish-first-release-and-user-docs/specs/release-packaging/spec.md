# Spec delta: release-packaging

## MODIFIED Requirements

### Requirement: the update site is published to gh-pages

The same tag run SHALL publish the contents of `repositories/target/repository`
to the `gh-pages` branch so the p2 site is served from the repository's GitHub
Pages root. The workflow SHALL carry `pages: write` permission and enable Pages
from `gh-pages`/root on first run, so the first release does not depend on
manual repository settings.

#### Scenario: site content lands on gh-pages

- **WHEN** the release run finishes
- **THEN** the `gh-pages` branch root contains `index.html`, `content.jar`,
  `artifacts.jar` and `p2.index`

#### Scenario: first release activates Pages by itself

- **WHEN** tag `v0.0.1` runs on a repository where Pages has never been enabled
- **THEN** the release job still succeeds
- **AND** `https://madmar1o.github.io/colleague/p2.index` answers HTTP 200
  within a few minutes

## ADDED Requirements

### Requirement: release version coordinates are explicit and frozen per tag

A release tag SHALL point at a commit whose pom/feature/manifest coordinates are
the plain release version (no `-SNAPSHOT`), and the version-bump commit SHALL
carry the `[allow-version-change]` marker so the version-freeze gate admits it.
After the release, the trunk SHALL return to the next `-SNAPSHOT`.

#### Scenario: v0.0.1 builds a 0.0.1.vDATE artifact

- **WHEN** tag `v0.0.1` is built
- **THEN** the feature id `com.mad.edt.colleague.feature` has version
  `0.0.1.vГГГГММДДччмм`
- **AND** no artifact in the site carries `SNAPSHOT`

#### Scenario: trunk returns to snapshot after release

- **WHEN** the v0.0.1 release is out
- **THEN** a subsequent PR sets all coordinates to `0.0.2-SNAPSHOT`
- **AND** CI stays green
