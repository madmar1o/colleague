# Spec delta: update-channel

## ADDED Requirements

### Requirement: one canonical install URL for the latest release

The project SHALL serve the latest released p2 site at
`https://madmar1o.github.io/colleague/` and document that exact URL in README,
CHANGELOG and docs; no machine-local file paths may appear as install sources
in published docs.

#### Scenario: URL stability across releases

- **WHEN** a newer release is published
- **THEN** the same URL serves the newer site without README changes

### Requirement: every release keeps an immutable archive

Each GitHub Release SHALL carry the p2 site zip and SHA256SUMS for its tag, so
a user can install a specific past version via «Add → Archive» even after the
hosted site moved on.

#### Scenario: install an older tag from its release page

- **WHEN** the user downloads `colleague-update-site-v0.0.1.zip` from the
  v0.0.1 release page and verifies SHA256SUMS
- **THEN** «Add → Archive» installs exactly the 0.0.1 feature
