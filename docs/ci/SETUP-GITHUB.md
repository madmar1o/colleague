# GitHub setup for the CI/CD pipeline

Everything below is a one-time setup. After it is done, `main` cannot receive
code that fails the build, the tests, the path-guard canon, the convention
gates (commit subjects, Javadoc on new public API, frozen build coordinates,
no machine-specific paths, no third-party product names) or the Sonar gate.

## 1. Repository secrets

| Secret | Value | Used by |
|---|---|---|
| `SONAR_TOKEN` | user token of the SonarQube instance (`%USERPROFILE%\.sonar\scan-token.txt` locally) | `ci.yml`, `nightly.yml` |

Set it with:

```powershell
gh secret set SONAR_TOKEN --repo madmar1o/colleague
```

The SonarQube server is published at `https://son.plaksin.crazedns.ru` so any
runner can reach it; the workflows pass that address to the scan and gate
scripts through `SONAR_HOST_URL`. A local run without the variable uses the
loopback instance (`http://127.0.0.1:9000`).

`GITHUB_TOKEN` is provided by Actions; no manual setup.

## 2. Self-hosted runner (required for build + Sonar jobs)

The SonarQube instance and the scanner live on this machine, and the Tycho
target platform is already cached there, so the heavy jobs run locally.

1. GitHub -> repository -> Settings -> Actions -> Runners -> **New self-hosted runner**.
2. Windows, x64. Run the shown commands in the runner install directory
   (any path; below `<RUNNER_DIR>` stands for the folder you chose):

```powershell
mkdir <RUNNER_DIR>; cd <RUNNER_DIR>   # example: a folder outside any repository
# download the runner archive from the page shown by GitHub, then:
Expand-Archive actions-runner-win-x64-*.zip -DestinationPath .
./config.cmd --url https://github.com/madmar1o/colleague --token <RUNNER_TOKEN> `
  --labels colleague --runasservice
```

3. The jobs select the runner with `runs-on: [self-hosted, windows, x64, colleague]`.
   Keep the label `colleague` - the workflows depend on it.
4. The runner must be able to: run `mvnw.cmd` on JDK 17 (point the JDK at the
   runner with `JAVA_HOME_17=<jdk17 home>` in the runner `.env` file, plain
   `JAVA_HOME` also works), `node`, and `docker` (the Sonar scanner image
   `sonarsource/sonar-scanner-cli:5`). No repository file hardcodes a machine path.

## 3. Branch protection for `main`

Branch protection on GitHub Free requires a **public** repository. Required:
pull request before merging, the `hygiene`, `build-test` and `sonar-gate`
checks green, no force pushes, no deletions, linear history. No approving
review is required: the repository owner merges once CI is green.

Via `gh` (after `gh auth login`):

```powershell
gh api -X PUT repos/madmar1o/colleague/branches/main/protection `
  -f required_status_checks.strict=true `
  -f required_status_checks.contexts[]=hygiene `
  -f required_status_checks.contexts[]=build-test `
  -f required_status_checks.contexts[]=sonar-gate `
  -f enforce_admins=true `
  -f required_pull_request_reviews.required_approving_review_count=0 `
  -f required_pull_request_reviews.dismiss_stale_reviews=true `
  -f restrictions= `
  -f allow_force_pushes=false `
  -f allow_deletions=false
```

Or in the UI: Settings -> Branches -> Add branch protection rule -> pattern `main`.

The repository is private: GitHub-hosted runner minutes come from a monthly
quota, while self-hosted jobs are unlimited. That is a further reason every
job runs on the single self-hosted runner. Also set Settings -> Actions ->
**Fork pull request workflows from outside collaborators: Require approval for
all outside collaborators**. All jobs are guarded by `if:` and skip fork pull
requests entirely: untrusted code never runs on the self-hosted machine. A fork
PR with changes to accept is cherry-picked by a maintainer onto a branch of
this repo.

Note: GitHub Pages (used by the release workflow to serve the p2 update site)
requires Pages to be enabled for the private repository, or the tag publish
step must be switched to a release asset download instead.

## 4. Local hooks (optional, defense in depth)

The repository does not ship machine-specific hooks. For local checks install
the neutral hooks from this repo or run the pipeline manually:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/run.ps1 -Stage all
```

## 5. Release

```powershell
git tag v0.1.0
git push origin v0.1.0
```

`release.yml` builds the p2 update site, attaches
`colleague-update-site-<tag>.zip` and `SHA256SUMS` to the GitHub Release and
publishes the update site to the `gh-pages` branch
(`https://madmar1o.github.io/colleague/`).

## 6. Nightly

`nightly.yml` runs at 05:00 Asia/Bangkok: full build, a scan of `main` into the
`colleague-edt` project, a drift report against `ci/sonar-baseline.json` and a
GitHub issue when main drifts or the gate fails.

## 7. Baseline

`ci/sonar-baseline.json` is the frozen issue set of `main`. It is regenerated
deliberately (one commit) after a batch of fixes is merged, so the gate keeps
meaning "nothing new and nothing regressed" instead of "everything from
scratch".
