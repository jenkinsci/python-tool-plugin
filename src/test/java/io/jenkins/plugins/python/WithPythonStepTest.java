package io.jenkins.plugins.python;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import hudson.model.Result;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@WithJenkins
class WithPythonStepTest {

    @Test
    void testWithPythonWithConfiguredTool(JenkinsRule jenkins) throws Exception {
        File dummyHome = jenkins.createTmpDir();
        new File(dummyHome, "bin").mkdirs();

        PythonInstallation tool = new PythonInstallation("MockPython", dummyHome.getAbsolutePath(), Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(PythonInstallation.DescriptorImpl.class).setInstallations(tool);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-with-python");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                "    withPython(tool: 'MockPython') {\n" +
                "        echo 'ENV_PYTHONHOME=' + env.PYTHONHOME\n" +
                "        echo 'ENV_PATH=' + env.PATH\n" +
                "    }\n" +
                "}", true));

        WorkflowRun build = jenkins.buildAndAssertStatus(Result.SUCCESS, job);
        assertNotNull(build);
        jenkins.assertLogContains("ENV_PYTHONHOME=" + dummyHome.getAbsolutePath(), build);
        jenkins.assertLogContains("[withPython] Injected Python environment for MockPython", build);
    }

    @Test
    void testWithPythonCustomHome(JenkinsRule jenkins) throws Exception {
        File customDir = jenkins.createTmpDir();

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-custom-home");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                "    withPython(home: '" + customDir.getAbsolutePath().replace("\\", "/") + "') {\n" +
                "        echo 'CUSTOM_HOME=' + env.PYTHONHOME\n" +
                "    }\n" +
                "}", true));

        WorkflowRun build = jenkins.buildAndAssertStatus(Result.SUCCESS, job);
        assertNotNull(build);
        jenkins.assertLogContains("CUSTOM_HOME=" + customDir.getAbsolutePath().replace("\\", "/"), build);
        jenkins.assertLogContains("[withPython] Using pre-configured Python home", build);
    }

    @Test
    void testWithPythonCudaEnvironment(JenkinsRule jenkins) throws Exception {
        File dummyHome = jenkins.createTmpDir();
        new File(dummyHome, "bin").mkdirs();

        PythonInstallation tool = new PythonInstallation("MockToolForCuda", dummyHome.getAbsolutePath(), Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(PythonInstallation.DescriptorImpl.class).setInstallations(tool);

        File dummyCuda = jenkins.createTmpDir();

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-cuda");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                "    withPython(tool: 'MockToolForCuda', cuda: true, cudaHome: '" + dummyCuda.getAbsolutePath().replace("\\", "/") + "') {\n" +
                "        echo 'CUDA_HOME_IS=' + env.CUDA_HOME\n" +
                "        echo 'CUDA_AVAIL=' + env.CUDA_AVAILABLE\n" +
                "    }\n" +
                "}", true));

        WorkflowRun build = jenkins.buildAndAssertStatus(Result.SUCCESS, job);
        assertNotNull(build);
        jenkins.assertLogContains("CUDA_HOME_IS=" + dummyCuda.getAbsolutePath().replace("\\", "/"), build);
        jenkins.assertLogContains("CUDA_AVAIL=1", build);
    }

    @Test
    void testWithPythonVirtualenv(JenkinsRule jenkins) throws Exception {
        File dummyHome = jenkins.createTmpDir();
        new File(dummyHome, "bin").mkdirs();

        PythonInstallation tool = new PythonInstallation("MockToolForVenv", dummyHome.getAbsolutePath(), Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(PythonInstallation.DescriptorImpl.class).setInstallations(tool);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-venv");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                "    writeFile file: 'my-venv/pyvenv.cfg', text: 'home = /mock\\nversion = 3.12.0\\n'\n" +
                "    withPython(tool: 'MockToolForVenv', venv: 'my-venv') {\n" +
                "        echo 'VENV_DIR=' + env.VIRTUAL_ENV\n" +
                "        echo 'VENV_PYTHONHOME=' + (env.PYTHONHOME ?: 'UNSET')\n" +
                "    }\n" +
                "}", true));

        WorkflowRun build = jenkins.buildAndAssertStatus(Result.SUCCESS, job);
        assertNotNull(build);
        jenkins.assertLogContains("VENV_DIR=", build);
        jenkins.assertLogContains("VENV_PYTHONHOME=UNSET", build);
        jenkins.assertLogContains("[withPython] Activated virtual environment", build);
    }

    @Test
    void testWithPythonVirtualenvExecution(JenkinsRule jenkins) throws Exception {
        File dummyHome = jenkins.createTmpDir();
        new File(dummyHome, "bin").mkdirs();

        PythonInstallation tool = new PythonInstallation("MockToolForWithVenv", dummyHome.getAbsolutePath(), Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(PythonInstallation.DescriptorImpl.class).setInstallations(tool);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-with-python-virtualenv-execution");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                "    writeFile file: '.venv/pyvenv.cfg', text: 'home = /mock\\nversion = 3.12.0\\n'\n" +
                "    withPython(tool: 'MockToolForWithVenv', venv: '.venv') {\n" +
                "        echo 'WITH_VENV_TARGET=' + env.VIRTUAL_ENV\n" +
                "        echo 'WITH_VENV_PYTHONHOME=' + (env.PYTHONHOME ?: 'UNSET')\n" +
                "    }\n" +
                "}", true));

        WorkflowRun build = jenkins.buildAndAssertStatus(Result.SUCCESS, job);
        assertNotNull(build);
        jenkins.assertLogContains("WITH_VENV_TARGET=", build);
        jenkins.assertLogContains("WITH_VENV_PYTHONHOME=UNSET", build);
        jenkins.assertLogContains("[withPython] Activated virtual environment", build);
    }

    @Test
    void testWithPythonUvEnvironment(JenkinsRule jenkins) throws Exception {
        File dummyHome = jenkins.createTmpDir();
        File binDir = new File(dummyHome, "bin");
        binDir.mkdirs();
        new File(binDir, "uv").createNewFile();
        File scriptsDir = new File(dummyHome, "Scripts");
        scriptsDir.mkdirs();
        new File(scriptsDir, "uv.exe").createNewFile();

        PythonInstallation tool = new PythonInstallation("MockToolWithUv", dummyHome.getAbsolutePath(), Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(PythonInstallation.DescriptorImpl.class).setInstallations(tool);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-with-python-uv");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                "    withPython(tool: 'MockToolWithUv', uv: true, uvCacheDir: '.uv-cache') {\n" +
                "        echo 'UV_PYTHON_IS=' + env.UV_PYTHON\n" +
                "        echo 'UV_CACHE_DIR_IS=' + env.UV_CACHE_DIR\n" +
                "    }\n" +
                "}", true));

        WorkflowRun build = jenkins.buildAndAssertStatus(Result.SUCCESS, job);
        assertNotNull(build);
        jenkins.assertLogContains("[withPython] Configured Astral uv environment", build);
        jenkins.assertLogContains("UV_PYTHON_IS=", build);
        jenkins.assertLogContains("UV_CACHE_DIR_IS=", build);
        jenkins.assertLogContains(".uv-cache", build);
    }

    @Test
    void testWithPythonUvAndVirtualenv(JenkinsRule jenkins) throws Exception {
        File dummyHome = jenkins.createTmpDir();
        File binDir = new File(dummyHome, "bin");
        binDir.mkdirs();
        new File(binDir, "uv").createNewFile();
        File scriptsDir = new File(dummyHome, "Scripts");
        scriptsDir.mkdirs();
        new File(scriptsDir, "uv.exe").createNewFile();

        PythonInstallation tool = new PythonInstallation("MockToolUvVenv", dummyHome.getAbsolutePath(), Collections.emptyList());
        jenkins.jenkins.getDescriptorByType(PythonInstallation.DescriptorImpl.class).setInstallations(tool);

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, "test-with-python-uv-venv");
        job.setDefinition(new CpsFlowDefinition(
                "node {\n" +
                "    writeFile file: '.venv-uv/pyvenv.cfg', text: 'home = /mock\\nversion = 3.12.0\\n'\n" +
                "    withPython(tool: 'MockToolUvVenv', venv: '.venv-uv', uv: true) {\n" +
                "        echo 'UV_VENV_DIR=' + env.VIRTUAL_ENV\n" +
                "        echo 'UV_VENV_PYTHON=' + env.UV_PYTHON\n" +
                "    }\n" +
                "}", true));

        WorkflowRun build = jenkins.buildAndAssertStatus(Result.SUCCESS, job);
        assertNotNull(build);
        jenkins.assertLogContains("[withPython] Configured Astral uv environment", build);
        jenkins.assertLogContains("[withPython] Activated virtual environment", build);
        jenkins.assertLogContains(".venv-uv", build);
    }
}
