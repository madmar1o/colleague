# Spec delta: runtime-acceptance

## ADDED Requirements

### Requirement: every PR proves the built feature installs into EDT

The PR pipeline SHALL contain an `install-check` job that runs the Eclipse p2
director to install the freshly built feature
`com.mad.edt.colleague.feature.feature.group` from `repositories/target/repository`
into a throwaway profile, and fails when resolution or installation errors occur.
This job needs no secrets and therefore also runs for fork PRs.

#### Scenario: broken p2 metadata fails the PR

- **WHEN** a PR produces a repository whose content.jar does not resolve
  (missing dependency, bad version range)
- **THEN** the `install-check` job fails with the director's error output
- **AND** the PR cannot be merged (required check)

#### Scenario: fork PR without secrets

- **WHEN** an external contributor opens a fork PR
- **THEN** `install-check` still runs and passes for a healthy build
- **AND** no repository secret is exposed to the fork's code

### Requirement: main and nightly run the live acceptance cycle

After each merge to `main` and every night, CI SHALL run a `live-acceptance`
job that: deploys the built jars into a **dedicated CI EDT instance** (never the
developer's live instance), launches it with the web bridge on a free port,
waits for the activation marker in that instance's `.metadata/.log`, runs
`bridge_smoke.ps1` expecting 8/8, and closes the instance. The job runs only on
trusted triggers (`push` to `main`, `workflow_dispatch`, schedule) — never for
fork PRs.

#### Scenario: merged dоработка is proven live

- **WHEN** a PR is merged into `main`
- **THEN** `live-acceptance` redeploys the new jars into the CI instance
- **AND** `bridge_smoke` reports 8/8 before the job turns green

#### Scenario: fork code never executes on the runner

- **WHEN** a fork PR is open
- **THEN** no live-acceptance job appears in its check list

### Requirement: acceptance scripts live in the repository and are machine-neutral

`colleague_deploy.ps1`, `colleague_launch_bridge.ps1`, `bridge_smoke.ps1` and
`test_path_guard.*` SHALL be tracked under `tools/` in the repository; the EDT
installation and workspace paths SHALL come from runner environment variables
(`EDT_CI_HOME`, `EDT_CI_PROFILE`, `JAVA_HOME_17`), never from hardcoded machine
paths (sanitize gate enforces).

#### Scenario: fresh clone runs acceptance on any EDT machine

- **WHEN** a user clones the repository and sets `EDT_CI_HOME` to their EDT 2026.1
- **THEN** the same scripts deploy and smoke-test without editing files

### Requirement: nightly acceptance failure files an issue with evidence

When `live-acceptance` fails on the nightly run, CI SHALL open a repository
issue containing the failing step, the smoke report and the log excerpt, as the
Sonar drift job does today.

#### Scenario: nightly regression is visible without reading Actions

- **WHEN** the nightly live-acceptance fails
- **THEN** a new issue titled with the date appears with the smoke report attached
