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
