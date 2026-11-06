# Python Tool Jenkins Plugin

[![CI](https://github.com/jenkinsci/python-tool-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jenkinsci/python-tool-plugin/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Jenkins](https://img.shields.io/badge/Jenkins-2.479%2B-blue.svg)](https://www.jenkins.io/)
[![SpotBugs](https://img.shields.io/badge/SpotBugs-Passed-brightgreen.svg)](https://spotbugs.github.io/)

A Jenkins plugin that automates standalone Python provisioning and runtime environment configuration across Windows, Linux, and macOS agents.

Similar to the NodeJS and Go tool plugins, this plugin enables Jenkins pipelines to dynamically provision specific standalone Python versions on-demand without requiring pre-installed system packages, package managers (`apt`, `brew`, `choco`), compiler toolchains, or administrator/root permissions.

---

## Key Highlights

- **Zero Agent Prerequisites**: Automatically downloads official, relocatable standalone CPython distributions from [python-build-standalone](https://github.com/astral-sh/python-build-standalone).
- **Native Astral `uv` & `uvx` Acceleration**: Provision Astral `uv` on-the-fly (`uv: true`) for **10x to 100x+ faster** virtual environments and package installations.
- **Cross-Platform**: Full support for Linux (`x86_64`, `aarch64`, `armv7`, `ppc64le`, `s390x`, `musl`), macOS (`Intel`, `Apple Silicon`), and Windows (`x86_64`, `arm64`, `x86`).
- **Flexible Pipeline Step**: `withPython(...)` supports dynamic version downloads, pre-configured tools, isolated virtual environments, CUDA GPU path injection, and corporate mirror URLs.
- **Configuration as Code (JCasC)**: Fully configurable via `jenkins.yaml` with automatic tool installation.

---

## Astral `uv` CI Acceleration: Performance & Benefits

In Python CI workflows, dependency resolution and package installation often consume 60% to 80% of total build time. This plugin integrates native Astral `uv` support (`uv: true`), replacing standard `pip` and `venv` operations with Astral's high-performance Rust toolchain.

### Empirical CI Benchmarks (Linux x86_64 Agent)

Measured inside Jenkins CI across 5 workload tiers (3 iterations each):

| Workload Tier | Resolved Packages | Standard `venv` vs `uv venv` | Standard `pip` (Warm Cache) | Astral `uv pip` (Warm Cache) | Warm Speedup | Standard `poetry` (Warm) | Astral `uv` vs Poetry |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`xsmall`** (CLI utility) | ~10 | 1.15s vs **0.01s** | 1.53s | **0.05s** | **33.3x faster** | 0.61s | **13.3x faster** |
| **`small`** (REST API) | ~25 | 1.14s vs **0.01s** | 2.92s | **0.06s** | **51.3x faster** | 1.04s | **18.3x faster** |
| **`medium`** (Web + DB + Redis) | ~50 | 1.16s vs **0.01s** | 6.00s | **0.09s** | **70.6x faster** | 2.01s | **23.7x faster** |
| **`large`** (Data Science / ML) | ~75 | 1.14s vs **0.01s** | 13.06s | **0.12s** | **108.0x faster** | 5.20s | **43.0x faster** |
| **`xlarge`** (Enterprise Stack) | ~110 | 1.19s vs **0.01s** | 17.59s | **0.34s** | **51.6x faster** | 8.99s | **26.4x faster** |

*Even on cold builds (clean agents without pre-cached wheels), Astral `uv` finishes package installations **1.5x to 3.6x faster** due to parallel network streaming and non-blocking wheel unpacking.*

### Why Use Astral `uv` in Your Pipelines?

- **Sub-second Environment Setup**: `uv venv` creates isolated virtual environments in **~10ms**, providing clean isolation for every stage or test matrix run without performance penalties.
- **Sub-second Repeat CI Builds**: On warm caches, installing even 75+ heavy packages (`numpy`, `pandas`, `scipy`, `scikit-learn`) drops from **13.06s down to 0.12s** (a **108x speedup**).
- **Drop-in Compatibility**: Run standard requirements files via `uv pip install -r requirements.txt`, lockfiles via `uv sync --frozen`, or invoke ephemeral tools via `uvx ruff check .`.
- **Persistent Workspace Cache**: Supply `uvCacheDir: '.uv-cache'` to maintain a shared, content-addressable wheel cache across executor runs.

---

## Pipeline Usage

### 1. Recommended: Declarative Pipeline with Astral `uv` & Virtualenv

```groovy
pipeline {
    agent any
    stages {
        stage('Build & Test') {
            steps {
                // Provisions standalone Python 3.12 and Astral uv, creating an isolated .venv
                withPython(version: '3.12', venv: '.venv', uv: true) {
                    sh 'uv pip install -r requirements.txt'
                    sh 'pytest tests/'
                }
            }
        }
        stage('Lockfile Sync & Ephemeral Tools') {
            steps {
                // Use uv sync for pyproject.toml projects and uvx for CLI utilities
                withPython(version: '3.12', uv: true, uvCacheDir: '.uv-cache') {
                    sh 'uv sync --frozen'
                    sh 'uv run pytest'
                    sh 'uvx ruff check .'
                }
            }
        }
    }
}
```

### 2. Standard Virtual Environment (Pip / Venv)

```groovy
pipeline {
    agent any
    stages {
        stage('Test') {
            steps {
                withPython(version: '3.12.15', venv: '.venv') {
                    sh 'pip install -r requirements.txt'
                    sh 'pytest tests/'
                }
            }
        }
    }
}
```

### 3. Declarative `tools` Directive

```groovy
pipeline {
    agent any
    tools {
        // References a pre-configured installation from Global Tool Configuration or JCasC
        python 'Python 3.12'
    }
    stages {
        stage('Test') {
            steps {
                sh 'python3 --version'
                sh 'pytest tests/'
            }
        }
    }
}
```

### 4. GPU Acceleration with CUDA (`cuda: true`)

```groovy
pipeline {
    agent any
    stages {
        stage('GPU Training') {
            steps {
                // Probes agent CUDA drivers, injecting CUDA_HOME, LD_LIBRARY_PATH, and PATH
                withPython(version: '3.12.15', cuda: true) {
                    sh 'python -c "import torch; print(torch.cuda.is_available())"'
                }
            }
        }
    }
}
```

### 5. Corporate Mirror & Air-Gapped Networks

```groovy
pipeline {
    agent any
    stages {
        stage('Internal Mirror') {
            steps {
                // Download standalone Python and uv binaries from internal Artifactory/Nexus mirrors
                withPython(
                    version: '3.12.15',
                    uv: true,
                    mirrorUrl: 'https://artifactory.corp.internal/python-dist/',
                    uvMirrorUrl: 'https://artifactory.corp.internal/uv-dist/'
                ) {
                    sh 'uv pip install --index-url https://pypi.corp.internal/simple -r requirements.txt'
                }
            }
        }
    }
}
```

---

## Configuration as Code (JCasC)

You can manage Python installations and automated Astral `uv` bundling declaratively using Jenkins Configuration as Code (`jenkins.yaml`):

```yaml
tool:
  python:
    installations:
      - name: "Python 3.12"
        properties:
          - installSource:
              installers:
                - pythonStandalone:
                    id: "3.12.15"
                    installUv: true
                    uvVersion: "latest" # or pin a specific version, e.g. "0.13.1"
      - name: "Python 3.11"
        properties:
          - installSource:
              installers:
                - pythonStandalone:
                    id: "3.11.10"
                    installUv: true
      - name: "Python 3.12 (Internal Mirror)"
        properties:
          - installSource:
              installers:
                - pythonStandalone:
                    id: "3.12.15"
                    installUv: true
                    mirrorUrl: "https://artifactory.corp.internal/python-dist/"
                    uvMirrorUrl: "https://artifactory.corp.internal/uv-dist/"
      - name: "System Python"
        home: "/usr/bin/python3"
```

---

## Global Tool Configuration (Jenkins UI)

1. Navigate to **Manage Jenkins** &rarr; **Tools**.
2. Scroll to **Python** and click **Add Python**.
3. Configure the name (e.g. `Python 3.12`).
4. Under **Install automatically**, select **Install Standalone Python from Astral-sh**.
5. Select your desired Python version (e.g. `3.12.15`).
6. *(Optional)* Check **Install Astral uv alongside Python** (and optionally set an **Astral uv Version**) to bundle `uv` and `uvx`.
7. Click **Save**.

---

## Step Parameters Reference (`withPython`)

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `version` | String | `null` | Exact (e.g. `'3.12.15'`) or prefix (e.g. `'3.12'`) Python version to dynamically provision. |
| `tool` | String | `null` | Name of pre-configured Python installation from Global Tool Configuration or JCasC. |
| `venv` | String / bool | `null` | Virtualenv directory path (e.g. `'.venv'`). When provided, creates and activates the venv (via `uv venv` when `uv` is enabled). |
| `uv` | boolean | `false` | When true, provisions Astral `uv` and `uvx` on `PATH` and binds `UV_PYTHON`. |
| `uvVersion` | String | `null` | Specific `uv` version to install (e.g. `'0.13.1'` or `'latest'`). Automatically enables `uv`. |
| `uvCacheDir` | String | `null` | Custom directory path for `uv` dependency cache (`UV_CACHE_DIR`). |
| `uvMirrorUrl` | String | `null` | Internal mirror base URL for Astral `uv` release archives. |
| `mirrorUrl` | String | `null` | Internal mirror base URL for standalone Python release archives. |
| `clear` | boolean | `false` | When true with `venv`, recreates the virtual environment from scratch. |
| `systemSitePackages` | boolean | `false` | When true with `venv`, gives the virtual environment access to system site-packages. |
| `system` | boolean | `false` | Discovers and uses the agent pre-installed system Python (`python3`/`python`). |
| `home` | String | `null` | Explicit directory path to an existing Python installation on the agent. |
| `cuda` | boolean | `false` | Probes agent CUDA toolkit and injects `CUDA_HOME`, `PATH`, and `LD_LIBRARY_PATH`. |
| `cudaHome` | String | `null` | Explicit path override for CUDA toolkit on the agent. |

---

## Air-Gapped & Custom Catalog Configuration

For air-gapped Jenkins controllers without outbound internet access:
- **Option 1**: Place `python-versions.json` directly into `$JENKINS_HOME/python-versions.json`.
- **Option 2**: Launch Jenkins with the system property:
  ```bash
  -Dio.jenkins.plugins.python.versionsFile=/var/jenkins_home/my-custom-versions.json
  ```
- **Option 3**: Configure internal URL mirrors for dynamic catalog updates and binary downloads:
  ```bash
  -Dio.jenkins.plugins.python.versionsUrl=https://internal-artifactory.corp/python/versions.json
  -Dio.jenkins.plugins.python.mirrorUrl=https://internal-artifactory.corp/python-dist/
  -Dio.jenkins.plugins.python.uvMirrorUrl=https://internal-artifactory.corp/uv-dist/
  ```

---

## Updating Upstream Python Versions

The plugin automatically checks and caches new releases from `astral-sh/python-build-standalone`. Maintainers can also manually update the bundled catalog:

```bash
python scripts/update-python-versions.py
```

---

## License

This plugin is licensed under the [MIT License](LICENSE).
