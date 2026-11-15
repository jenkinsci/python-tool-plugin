# AGENTS.md — Jenkins Python Tool & Pipeline Plugin (`python-tool`)

## Overview
- **Plugin ID**: `python-tool`
- **Repository**: `jenkinsci/python-tool-plugin`
- **Java Package**: `io.jenkins.plugins.python`
- **Jenkins Baseline**: `2.555.x` (`org.jenkins-ci.plugins:plugin:6.2250.va_d01d559a_209`, Java 17 / 21+)

Provides global Python tool management (`PythonInstallation`), automated cross-platform Python & `uv` installation (`PythonInstaller`, `UvInstaller`), and the `withPython` Pipeline step (`WithPythonStep`) with automatic virtual environment creation and `PATH` / `VIRTUAL_ENV` injection.

---

## Build, Test & Verification Commands

### Prerequisites
- **JDK**: Java 21 (recommended) or Java 17+
- **Maven**: Apache Maven `3.9+`

### Core Maven Commands
```bash
# Full build, unit/integration tests, SpotBugs, and Enforcer checks (always run before opening a PR)
mvn clean verify

# Run unit tests quickly (skipping slow InjectedTest Jelly scan during development)
mvn test -Dtest=!InjectedTest

# Run a specific test class
mvn test -Dtest=WithPythonStepTest
mvn test -Dtest=PythonInstallerTest

# Run SpotBugs and Maven Enforcer checks directly
mvn spotbugs:check enforcer:enforce

# Package the .hpi plugin artifact without running tests
mvn clean package -DskipTests

# Launch a local Jenkins instance at http://localhost:8080/jenkins
mvn hpi:run
```

---

## Architecture & Source Layout

- `src/main/java/io/jenkins/plugins/python/`
  - `PythonInstallation.java` — `ToolInstallation` (`EnvironmentSpecific`, `NodeSpecific`) defining global Python installations and default installer registration.
  - `PythonInstaller.java` — `ToolInstaller` provisioning standalone Python distributions across Linux, macOS, and Windows agents.
  - `UvInstaller.java` — `ToolInstaller` provisioning Astral's `uv` package/environment manager.
  - `PythonVersionCatalog.java` — Resolves version metadata and platform download URLs from `versions.json`.
  - `PlatformUtils.java` & `AgentProbeUtils.java` — Remote agent OS/CPU architecture detection and Python binary probing over Jenkins Remoting.
  - `WithPythonStep.java` & `WithPythonStepExecution.java` — Pipeline step (`withPython`) providing tool resolution, optional `venv` setup, and environment variable injection (`PATH`, `PYTHONHOME`, `VIRTUAL_ENV`).
- `src/main/resources/io/jenkins/plugins/python/`
  - `versions.json` — Bundled Python version catalog.
  - `PythonInstallation/config.jelly`, `PythonInstaller/config.jelly`, `WithPythonStep/config.jelly`, `help-*.html`.
- `src/test/java/io/jenkins/plugins/python/`
  - `WithPythonStepTest.java`, `PythonInstallationTest.java`, `PythonInstallerTest.java`, `UvInstallerTest.java`, `PythonVersionCatalogTest.java`, `PlatformUtilsTest.java`, `AgentProbeUtilsTest.java`.

---

## Coding, Remoting & Security Guardrails

1. **Jenkins Remoting & Agent File I/O**:
   - Never use `java.io.File` for paths on build agents. Always use `hudson.FilePath` and `hudson.Launcher` so tool installation and `withPython` execution work seamlessly on remote Linux, macOS, and Windows agents.
   - Ensure any `MasterToSlaveCallable` or `StepExecution` fields are `Serializable` (or marked `transient`).
2. **Cross-Platform Path Handling**:
   - Handle both POSIX (`bin/python3`, `:`) and Windows (`Scripts\python.exe`, `python.exe`, `;`) binary paths and path separators via `PlatformUtils`.
3. **UI & Static Analysis Standards**:
   - Never use raw emojis in UI strings or Jelly templates; use Jenkins Symbols or SVG icons.
   - Never use native browser `alert()`, `confirm()`, or `prompt()`.
   - Keep BOM/parent POM version updates separate from feature/bugfix PRs unless required by an API change, and ensure `mvn spotbugs:check` reports 0 warnings.
