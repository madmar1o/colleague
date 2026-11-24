# release-packaging — baseline capability spec

(Baseline заведен 2026-11-23 при открытии change `publish-first-release-and-user-docs`,
чтобы зафиксировать уже реализованное поведение `release.yml` до его изменения.)

## Requirements

### Requirement: a v-tag produces a GitHub Release with the p2 site archive

Pushing a tag matching `v*` SHALL build the reactor, archive
`repositories/target/repository` as `colleague-update-site-<tag>.zip`, generate
SHA256SUMS and release notes from conventional commits, and publish a GitHub
Release carrying the zip and SHA256SUMS.

#### Scenario: tag push publishes the release

- **WHEN** tag `v0.0.1` is pushed
- **THEN** a GitHub Release named `Colleague v0.0.1` exists with assets
  `colleague-update-site-v0.0.1.zip` and `SHA256SUMS`
- **AND** the notes group commits under Features/Fixes/… headings

### Requirement: the update site is published to gh-pages

The same tag run SHALL publish the contents of `repositories/target/repository`
to the `gh-pages` branch so the p2 site is served from the repository's GitHub
Pages root.

#### Scenario: site content lands on gh-pages

- **WHEN** the release run finishes
- **THEN** the `gh-pages` branch root contains `index.html`, `content.jar`,
  `artifacts.jar` and `p2.index`
