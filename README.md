# Python Tool Jenkins Plugin

[![CI](https://github.com/reder9/python-tool-jenkins-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/reder9/python-tool-jenkins-plugin/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Jenkins](https://img.shields.io/badge/Jenkins-2.479%2B-blue.svg)](https://www.jenkins.io/)
[![SpotBugs](https://img.shields.io/badge/SpotBugs-Passed-brightgreen.svg)](https://spotbugs.github.io/)

A production-grade Jenkins plugin that automates Python tool installation and runtime environment configuration across Windows, Linux, and macOS agents.

Similar to the Jenkins NodeJS and Go tool plugins, this plugin enables Jenkins jobs to dynamically provision specific standalone Python versions without requiring pre-installed system packages, package managers (`apt`, `brew`, `choco`), build toolchains, or administrator/root permissions.

---

## Comparison with Existing Jenkins Python Plugins

| Feature / Capability | Python Tool Plugin (This Plugin) | ShiningPanda Plugin | Pyenv Pipeline Plugin |
|---|---|---|---|
| **Distribution Source** | Official pre-built standalone relocatable binaries from `astral-sh/python-build-standalone` | Host system Python paths or legacy installers | Compiles Python from source on agent via `pyenv` |
| **System Prerequisites** | **Zero**. No compilers, no system headers, no root/admin access required | Requires Python/virtualenv already installed on agents | Requires `git`, `pyenv`, `gcc`, `make`, OpenSSL/zlib development libraries |
| **Platform Architecture Support** | **Linux** (`x86_64`, `aarch64`, `armv7`, `ppc64le`, `s390x`, `musl`/Alpine)<br>**macOS** (`x86_64`, `aarch64` Apple Silicon)<br>**Windows** (`x86_64`, `aarch64`, `x86`) | Platform-dependent based on pre-installed agent binaries | Primarily Linux/macOS (requires `pyenv-win` on Windows) |
| **Coexistence & Tool Namespace** | Tool registered as **Python** (`@Symbol("python")`) with automatic fallback support | Registered as "Python" | Re-uses ShiningPanda tool descriptors |
| **Pipeline Integration** | Native modern pipeline step: `withPython(...)` supporting dynamic downloads, inline venv, **Astral `uv` / `uvx`**, and CUDA | Freestyle Builders and legacy pipeline wrappers | `withPythonEnv(...)` wrapper around pyenv |
| **Package & Project Management** | Built-in **Astral `uv` & `uvx`** provisioning (10x–100x faster than `pip`) + standard `pip`/`venv` | Standard `pip` / `virtualenv` | Standard `pip` |
| **Corporate Proxies & Air-Gapped** | Full Jenkins proxy support (`ProxyConfiguration`), `mirrorUrl`, `uvMirrorUrl`, dynamic update caching, and local `python-versions.json` override | Relies on Jenkins proxy configuration | Depends on access to pyenv github repositories |

---

## Features

- **Automated Standalone Provisioning**: Downloads and unpacks self-contained standalone CPython distributions powered by Astral (`python-build-standalone`).
- **Native Astral `uv` & `uvx` Integration**:
  - `uv: true` (or `uvVersion: '0.13.1'`): Dynamically provisions and caches standalone `uv` and `uvx` binaries on the agent across all supported platforms.
  - Automatically binds `UV_PYTHON` to the active Python executable (or active `.venv`) so `uv pip`, `uv sync`, and `uv run` target the exact Jenkins-provisioned interpreter without redundant downloads.
  - When combined with `venv: '.venv'`, uses `uv venv` for near-instant (~50ms) virtual environment creation.
- **Comprehensive Cross-Platform Support**:
  - **Linux**: `linux-x86_64`, `linux-aarch64`, `linux-armv7`, `linux-ppc64le` (IBM Power), `linux-s390x` (IBM Z), and `musl` (Alpine Linux).
  - **macOS**: `macos-x86_64` (Intel), `macos-aarch64` (Apple Silicon M-series).
  - **Windows**: `windows-x86_64` (64-bit), `windows-aarch64` (ARM64), `windows-x86` (32-bit).
- **Early Platform Validation**: Fails early with clear error diagnostics if run on an unsupported OS or CPU architecture instead of downloading incompatible binaries.
- **Pipeline Integration**:
  - `withPython(version: '3.12.15')`: Dynamically downloads and configures Python on-the-fly.
  - `withPython(version: '3.12', uv: true)`: Provisions both Python and Astral `uv` / `uvx` on `PATH`.
  - `withPython(tool: 'Python 3.12')`: Uses a pre-configured Python installation from Global Tool Configuration.
  - `withPython(version: '3.12', venv: '.venv')`: Transparently provisions and activates an isolated virtual environment.
  - Declarative `tools { python '...' }` directive support.
- **Environment Management**:
  - Injects Python executable and scripts directory into `PATH`.
  - Sets `PYTHONHOME` to the standalone distribution root (and unsets it cleanly inside virtual environments).
  - Sets `PYTHONUNBUFFERED=1` for real-time Jenkins console output streaming.
  - Sets `UV_PYTHON` and optional `UV_CACHE_DIR` when `uv` is enabled.
- **Corporate Proxy & Air-Gapped Support**:
  - Fully honors Jenkins proxy settings via `ProxyConfiguration.open(URL)`.
  - `mirrorUrl` & `uvMirrorUrl`: Download standalone Python and `uv` archives from internal Artifactory/Nexus mirrors.
  - Local catalog override: Place `python-versions.json` in `$JENKINS_HOME` or set `-Dio.jenkins.plugins.python.versionsFile=/path/to/versions.json`.
- **Dynamic Catalog Fetching**:
  - Periodically checks and caches upstream version updates without requiring a new plugin release.
  - Gracefully falls back to cached or bundled version catalogs when offline.
- **CUDA / GPU Acceleration**:
  - `cuda: true`: Automatically probes agent CUDA drivers and toolkits (`CUDA_HOME`, `/usr/local/cuda`, `nvcc`), injecting CUDA paths and libraries (`LD_LIBRARY_PATH`, `PATH`) with graceful fallback to CPU.

---

## Astral `uv` vs Standard Pip & Poetry Performance Benchmarks

Empirical performance benchmarks measured inside Jenkins CI across **5 workload tiers** (3 iterations each):
- **Host / Agent**: `Linux x86_64`
- **Interpreters**: `Python 3.12.15` | `uv 0.13.7 (x86_64-unknown-linux-gnu)` | `Poetry (version 2.5.1)`
- **Benchmark Date**: 2026-11-06 02:05:28 UTC

### Benchmark Workload Tiers

| Workload Tier | Description | Key Stack Dependencies |
| :--- | :--- | :--- |
| **`xsmall`** | CLI / Micro Utility Stack (~3 direct, ~10 resolved packages) | `requests==2.32.3`, `click==8.1.7`, `rich==13.9.4` |
| **`small`** | Modern REST API Microservice (~5 direct, ~25 resolved packages) | `fastapi==0.115.4`, `uvicorn[standard]==0.32.0`, `pydantic==2.9.2`, `httpx==0.27.2`, `pytest==8.3.3` |
| **`medium`** | Full-Stack Web, Async Worker, DB & Redis (~11 direct, ~50 resolved packages) | `fastapi==0.115.4`, `uvicorn[standard]==0.32.0`, `pydantic==2.9.2`, `pydantic-settings==2.6.1`, `sqlalchemy==2.0.36`, `alembic==1.13.3`, and 5 more |
| **`large`** | Data Science, Analytics & API Stack (~10 direct, ~75 resolved packages) | `numpy==2.1.3`, `pandas==2.2.3`, `scipy==1.14.1`, `scikit-learn==1.5.2`, `matplotlib==3.9.2`, `seaborn==0.13.2`, and 4 more |
| **`xlarge`** | Enterprise Multi-Stack: Polars, ML, Cloud SDK & DB (~17 direct, ~110 resolved packages) | `polars==1.13.1`, `pyarrow==18.0.0`, `numpy==2.1.3`, `pandas==2.2.3`, `scipy==1.14.1`, `scikit-learn==1.5.2`, and 11 more |

### 1. Virtual Environment Provisioning (`python -m venv` vs `uv venv`)

| Workload Tier | Standard `venv` (Mean ± Std) | Astral `uv venv` (Mean ± Std) | Speedup Multiplier |
| :--- | :---: | :---: | :---: |
| **`xsmall`** | 1.15s ± 0.03s | **0.01s ± 0.00s** | **127.7x faster** |
| **`small`** | 1.14s ± 0.01s | **0.01s ± 0.00s** | **126.1x faster** |
| **`medium`** | 1.16s ± 0.01s | **0.01s ± 0.00s** | **128.3x faster** |
| **`large`** | 1.14s ± 0.01s | **0.01s ± 0.00s** | **126.9x faster** |
| **`xlarge`** | 1.19s ± 0.03s | **0.01s ± 0.00s** | **132.1x faster** |

### 2. Cold-Cache Package Installation (No-Cache / Fresh Build)

| Workload Tier | Standard `pip install --no-cache-dir` | Astral `uv pip install --no-cache` | Speedup Multiplier |
| :--- | :---: | :---: | :---: |
| **`xsmall`** | 2.98s ± 0.15s | **0.83s ± 0.33s** | **3.6x faster** |
| **`small`** | 5.51s ± 0.96s | **2.45s ± 0.41s** | **2.3x faster** |
| **`medium`** | 10.20s ± 2.20s | **3.55s ± 0.89s** | **2.9x faster** |
| **`large`** | 28.84s ± 0.39s | **13.28s ± 1.42s** | **2.2x faster** |
| **`xlarge`** | 53.40s ± 2.65s | **35.43s ± 11.14s** | **1.5x faster** |

### 3. Warm-Cache Package Installation (Pre-cached Wheels / Re-installs)

| Workload Tier | Standard `pip install` (Cached) | Astral `uv pip install` (Cached) | Speedup Multiplier |
| :--- | :---: | :---: | :---: |
| **`xsmall`** | 1.53s ± 0.14s | **0.05s ± 0.00s** | **33.3x faster** |
| **`small`** | 2.92s ± 0.31s | **0.06s ± 0.00s** | **51.3x faster** |
| **`medium`** | 6.00s ± 0.86s | **0.09s ± 0.01s** | **70.6x faster** |
| **`large`** | 13.06s ± 0.28s | **0.12s ± 0.01s** | **108.0x faster** |
| **`xlarge`** | 17.59s ± 0.93s | **0.34s ± 0.01s** | **51.6x faster** |

### 4. Poetry vs Astral `uv` CI Installation

| Workload Tier | Standard `poetry install` (Cold) | Astral `uv` (Cold) | Cold Speedup | Standard `poetry install` (Warm) | Astral `uv` (Warm) | Warm Speedup |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **`xsmall`** | 1.13s | **0.83s** | **1.4x** | 0.61s | **0.05s** | **13.3x** |
| **`small`** | 2.24s | **2.45s** | **0.9x** | 1.04s | **0.06s** | **18.3x** |
| **`medium`** | 3.49s | **3.55s** | **1.0x** | 2.01s | **0.09s** | **23.7x** |
| **`large`** | 14.61s | **13.28s** | **1.1x** | 5.20s | **0.12s** | **43.0x** |
| **`xlarge`** | 28.68s | **35.43s** | **0.8x** | 8.99s | **0.34s** | **26.4x** |

### Key Benchmark Takeaways

- **Virtual Environment Creation**: Astral `uv venv` creates clean environments in **~10ms (0.01s)**, which is **126x to 132x faster** than standard `python -m venv` (~1.15s).
- **Repeat CI Builds (Warm Cache)**: In standard CI pipelines where dependencies are cached, Astral `uv` installs packages in **0.05s to 0.34s** across all workload sizes, providing **33x to 108x speedups** over standard `pip`.
- **Cold CI Builds (No Cache)**: Even without pre-existing cache, Astral `uv` completes downloads and builds **1.5x to 3.6x faster** than `pip`.
- **Poetry Workloads**: Astral `uv` warm installations run **13x to 43x faster** than standard `poetry install` (e.g. 0.12s vs 5.20s on the 75-package `large` tier).

---

## Pipeline Usage

### 1. Declarative Pipeline (`withPython` Step)

```groovy
pipeline {
    agent any
    stages {
        stage('Test with Python') {
            steps {
                // Dynamically resolve and provision standalone Python 3.12.15
                withPython(version: '3.12.15') {
                    sh 'python3 --version'
                    sh 'pip --version'
                    sh 'python3 -c "import sys; print(sys.executable)"'
                }
            }
        }
    }
}
```

### 2. Isolated Virtual Environment

```groovy
pipeline {
    agent any
    stages {
        stage('Build & Test') {
            steps {
                // Automatically creates and activates .venv in the workspace
                withPython(version: '3.12.15', venv: '.venv') {
                    sh 'pip install pytest'
                    sh 'pytest tests/'
                }
            }
        }
    }
}
```

### 3. Ultra-Fast Package & Project Management with Astral `uv` (`uv: true`)

Enable `uv: true` (or specify `uvVersion`) to provision Astral's Rust-based `uv` and `uvx` CLIs alongside Python. When `venv` is also specified, virtual environment creation automatically uses `uv venv` and binds `UV_PYTHON` to the virtual environment:

```groovy
pipeline {
    agent any
    stages {
        stage('Fast venv + uv pip') {
            steps {
                // Creates .venv via `uv venv` and installs dependencies 10x-100x faster than pip
                withPython(version: '3.12', venv: '.venv', uv: true) {
                    sh 'uv --version'
                    sh 'uv pip install -r requirements.txt'
                    sh 'pytest tests/'
                }
            }
        }
        stage('Lockfile Sync & Ephemeral Tools (uv sync / uvx)') {
            steps {
                // Pin a specific uv version and customize the uv cache directory
                withPython(version: '3.12', uvVersion: '0.13.1', uvCacheDir: '.uv-cache') {
                    sh 'uv sync --frozen'
                    sh 'uv run pytest'
                    sh 'uvx ruff check .'
                }
            }
        }
    }
}
```

### 4. Declarative Pipeline (`tools` Block)

```groovy
pipeline {
    agent any
    tools {
        // References a configured tool installation in Manage Jenkins -> Tools
        python 'Python 3.12'
    }
    stages {
        stage('Build & Test') {
            steps {
                sh 'python3 -m unittest discover tests'
            }
        }
    }
}
```

### 5. Cross-Platform Declarative Pipeline (Windows & Unix)

```groovy
pipeline {
    agent any
    stages {
        stage('Run Python') {
            steps {
                withPython(version: '3.12.15') {
                    script {
                        if (isUnix()) {
                            sh 'python3 -c "import platform; print(\"Running on:\", platform.platform())"'
                        } else {
                            bat 'python -c "import platform; print(\"Running on:\", platform.platform())"'
                        }
                    }
                }
            }
        }
    }
}
```

### 6. Deep Learning / GPU Acceleration with CUDA (`cuda: true`)

```groovy
pipeline {
    agent any
    stages {
        stage('GPU Training & Inference') {
            steps {
                // Auto-detects agent CUDA installation, injects CUDA_HOME and PATH, with CPU fallback
                withPython(version: '3.12.15', cuda: true) {
                    sh 'python -c "import torch; print(\"CUDA Available:\", torch.cuda.is_available())"'
                }
            }
        }
    }
}
```

### 7. Corporate Mirror / Air-Gapped Networks (`mirrorUrl`, `uvMirrorUrl` & `system`)

```groovy
pipeline {
    agent any
    stages {
        stage('Internal Mirror') {
            steps {
                // Fetch standalone Python and uv archives from internal Artifactory/Nexus mirrors
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
        stage('Use Pre-installed System Python') {
            steps {
                withPython(system: true) {
                    sh 'python3 --version'
                }
            }
        }
    }
}
```

---

## Step Parameters Reference (`withPython`)

| Parameter | Type | Default | Description |
|---|---|---|---|
| `version` | String | `null` | Exact (e.g. `3.12.15`) or prefix (e.g. `3.12`) Python version to dynamically download and provision. |
| `tool` | String | `null` | Name of pre-configured Python installation from Global Tool Configuration. |
| `venv` | String / bool | `null` | Name or path of virtual environment (e.g. `'.venv'`). When provided, creates and activates the venv (using `uv venv` when `uv` is enabled). |
| `uv` | boolean | `false` | When true, provisions Astral `uv` and `uvx` on `PATH` and sets `UV_PYTHON` to the resolved Python binary. |
| `uvVersion` | String | `null` | Specific `uv` release version to provision (e.g. `'0.13.1'` or `'latest'`). Specifying `uvVersion` automatically enables `uv`. |
| `uvMirrorUrl` | String | `null` | Base URL of internal corporate mirror hosting Astral `uv` release archives (falls back to `mirrorUrl` or `-Dio.jenkins.plugins.python.uvMirrorUrl`). |
| `uvCacheDir` | String | `null` | Custom directory path for `uv`'s dependency cache (exports `UV_CACHE_DIR`). Relative paths resolve inside the build workspace. |
| `system` | boolean | `false` | When true, discovers and uses agent's pre-installed system Python (`python3`/`python`). |
| `home` | String | `null` | Explicit directory path to an existing Python installation on the agent. |
| `clear` | boolean | `false` | When true with `venv`, recreates the virtual environment from scratch. |
| `systemSitePackages` | boolean | `false` | When true with `venv`, gives the virtual environment access to system site-packages. |
| `mirrorUrl`| String | `null` | Base URL of internal corporate mirror hosting python-build-standalone archives. |
| `cuda` | boolean | `false` | When true, probes and injects CUDA GPU environment variables (`CUDA_HOME`, `PATH`, `LD_LIBRARY_PATH`). |
| `cudaHome` | String | `null` | Explicit path override for CUDA toolkit on the agent. |

---

## Global Tool Configuration (Jenkins UI)

1. Navigate to **Manage Jenkins** &rarr; **Tools**.
2. Scroll to **Python**.
3. Click **Add Python**.
4. Configure name (e.g., `Python 3.12`).
5. Select **Install automatically** &rarr; **Install Standalone Python from Astral-sh**.
6. Select your desired Python version from the dropdown list.
7. *(Optional)* Check **Install Astral uv alongside Python** (and optionally specify an **Astral uv Version**) to bundle `uv` and `uvx` directly inside the tool's `bin/` or `Scripts/` directory.
8. Click **Save**.

---

## Air-Gapped & Custom Catalog Configuration

For air-gapped Jenkins controllers without outbound internet access:
- **Option 1**: Place `python-versions.json` directly into `$JENKINS_HOME/python-versions.json`.
- **Option 2**: Launch Jenkins with the system property:
  ```
  -Dio.jenkins.plugins.python.versionsFile=/var/jenkins_home/my-custom-versions.json
  ```
- **Option 3**: Configure internal URL mirrors for dynamic catalog updates and binary downloads:
  ```
  -Dio.jenkins.plugins.python.versionsUrl=https://internal-artifactory.corp/python/versions.json
  -Dio.jenkins.plugins.python.mirrorUrl=https://internal-artifactory.corp/python-dist/
  -Dio.jenkins.plugins.python.uvMirrorUrl=https://internal-artifactory.corp/uv-dist/
  ```

---

## Updating Upstream Python Versions

The plugin automatically checks and caches new releases from `astral-sh/python-build-standalone`. Maintainers can also update the bundled catalog:

```bash
python scripts/update-python-versions.py
```

---

## License

This plugin is licensed under the [MIT License](LICENSE).
