package com.qqmu.jargus.test;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单元测试执行器
 *
 * 支持：
 * - Maven 项目：mvn test
 * - Gradle 项目：gradle test
 * - 输出结果解析
 */
@Slf4j
@Component
public class UnitTestRunner {

    /**
     * 单元测试结果
     */
    @Data
    @Builder
    public static class TestResult {
        private boolean success;
        private int testsRun;
        private int testsPassed;
        private int testsFailed;
        private int testsError;
        private int testsSkipped;
        private long durationMs;
        private String output;
        private String errorMessage;
        private List<TestFailure> failures;
    }

    /**
     * 测试失败信息
     */
    @Data
    @Builder
    public static class TestFailure {
        private String className;
        private String methodName;
        private String failureType;
        private String message;
        private String stackTrace;
    }

    /**
     * 执行单元测试
     *
     * @param projectPath  项目路径
     * @param timeoutSec   超时时间（秒）
     * @return 测试结果
     */
    public TestResult runTests(Path projectPath, int timeoutSec) {
        // 检测构建工具
        BuildTool buildTool = detectBuildTool(projectPath);

        return switch (buildTool) {
            case MAVEN -> runMavenTests(projectPath, timeoutSec);
            case GRADLE -> runGradleTests(projectPath, timeoutSec);
            case UNKNOWN -> TestResult.builder()
                    .success(false)
                    .errorMessage("未检测到构建工具（Maven/Gradle），无法执行单元测试")
                    .build();
        };
    }

    /**
     * 检测构建工具
     */
    private BuildTool detectBuildTool(Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (Files.exists(projectPath.resolve("build.gradle"))
                || Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        // 向上查找
        Path parent = projectPath.getParent();
        if (parent != null) {
            if (Files.exists(parent.resolve("pom.xml"))) {
                return BuildTool.MAVEN;
            }
        }
        return BuildTool.UNKNOWN;
    }

    /**
     * 执行 Maven 测试
     */
    private TestResult runMavenTests(Path projectPath, int timeoutSec) {
        long startTime = System.currentTimeMillis();

        try {
            // 查找 pom.xml 所在目录
            Path workingDir = findPomDir(projectPath);

            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "test",
                    "-DskipTests=false",
                    "-q",  // 安静模式，减少输出
                    "--batch-mode"
            );
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        log.debug("[mvn] {}", line);
                    }
                } catch (IOException e) {
                    log.debug("读取 Maven 输出失败: {}", e.getMessage());
                }
            });
            outputThread.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                killProcessTree(process);
                outputThread.interrupt();
                // 等输出线程收尾，避免残留线程持有被销毁进程的流句柄
                try {
                    outputThread.join(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return TestResult.builder()
                        .success(false)
                        .errorMessage("单元测试执行超时（" + timeoutSec + "秒）")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .output(output.toString())
                        .build();
            }

            int exitCode = process.exitValue();
            outputThread.join(5000);

            String outputStr = output.toString();

            // 解析测试结果
            TestResult result = parseMavenOutput(outputStr);
            result.setDurationMs(System.currentTimeMillis() - startTime);
            result.setOutput(outputStr);
            result.setSuccess(exitCode == 0);

            return result;

        } catch (Exception e) {
            log.error("执行 Maven 测试失败: {}", e.getMessage(), e);
            return TestResult.builder()
                    .success(false)
                    .errorMessage("执行 Maven 测试失败: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 执行 Gradle 测试
     */
    private TestResult runGradleTests(Path projectPath, int timeoutSec) {
        long startTime = System.currentTimeMillis();

        try {
            Path workingDir = findGradleDir(projectPath);

            ProcessBuilder pb = new ProcessBuilder(
                    "gradle", "test", "--quiet"
            );
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // StringBuffer 同步：超时分支主线程 output.toString() 时输出线程可能还在 append
            StringBuffer output = new StringBuffer();
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException ignored) {
                }
            });
            outputThread.start();

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                killProcessTree(process);
                outputThread.interrupt();
                // 与 Maven 分支对齐：等输出线程收尾，避免残留线程持有被销毁进程的流句柄
                try {
                    outputThread.join(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return TestResult.builder()
                        .success(false)
                        .errorMessage("单元测试执行超时（" + timeoutSec + "秒）")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .output(output.toString())
                        .build();
            }

            int exitCode = process.exitValue();
            outputThread.join(5000);

            TestResult result = parseGradleOutput(output.toString());
            result.setSuccess(exitCode == 0);
            result.setDurationMs(System.currentTimeMillis() - startTime);
            result.setOutput(output.toString());

            return result;

        } catch (Exception e) {
            log.error("执行 Gradle 测试失败: {}", e.getMessage());
            return TestResult.builder()
                    .success(false)
                    .errorMessage("执行 Gradle 测试失败: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * 解析 Maven 测试输出
     */
    private TestResult parseMavenOutput(String output) {
        TestResult.TestResultBuilder builder = TestResult.builder();
        List<TestFailure> failureList = new ArrayList<>();

        int testsRun = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;

        // 匹配 Maven Surefire 输出格式: Tests run: X, Failures: Y, Errors: Z, Skipped: W。
        // Surefire 每个测试类都会打印一行，末尾 Results: 段还有一行聚合；
        // 全量累加会翻倍——存在 Results: 段时只解析其后的聚合行
        String scope = output;
        int resultsIdx = output.lastIndexOf("Results:");
        if (resultsIdx >= 0) {
            scope = output.substring(resultsIdx);
        }
        Pattern pattern = Pattern.compile(
                "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)"
        );
        Matcher matcher = pattern.matcher(scope);
        while (matcher.find()) {
            testsRun += Integer.parseInt(matcher.group(1));
            failures += Integer.parseInt(matcher.group(2));
            errors += Integer.parseInt(matcher.group(3));
            skipped += Integer.parseInt(matcher.group(4));
        }

        builder.testsRun(testsRun);
        builder.testsFailed(failures);
        builder.testsError(errors);
        builder.testsSkipped(skipped);
        builder.testsPassed(testsRun - failures - errors);
        builder.failures(failureList);

        return builder.build();
    }

    /**
     * 解析 Gradle 测试输出
     */
    private TestResult parseGradleOutput(String output) {
        TestResult.TestResultBuilder builder = TestResult.builder();

        int tests = 0;
        int failed = 0;

        // 简单解析
        Pattern pattern = Pattern.compile("(\\d+) tests? completed");
        Matcher matcher = pattern.matcher(output);
        if (matcher.find()) {
            tests = Integer.parseInt(matcher.group(1));
        }

        if (output.contains("FAILED") || output.contains("FAILURE")) {
            failed = Math.max(1, tests / 10); // 估算
        }

        builder.testsRun(tests);
        builder.testsFailed(failed);
        builder.testsPassed(tests - failed);
        builder.failures(new ArrayList<>());

        return builder.build();
    }

    private Path findPomDir(Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return projectPath;
        }
        Path parent = projectPath.getParent();
        if (parent != null && Files.exists(parent.resolve("pom.xml"))) {
            return parent;
        }
        return projectPath;
    }

    private Path findGradleDir(Path projectPath) {
        if (Files.exists(projectPath.resolve("build.gradle"))
                || Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return projectPath;
        }
        Path parent = projectPath.getParent();
        if (parent != null && (Files.exists(parent.resolve("build.gradle"))
                || Files.exists(parent.resolve("build.gradle.kts")))) {
            return parent;
        }
        return projectPath;
    }

    /**
     * 超时后杀整棵进程树：destroyForcibly 只结束 mvn/gradle 本身，
     * 其 fork 的 surefire JVM / Gradle daemon 会变成孤儿进程继续跑测试占 CPU。
     */
    private static void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private enum BuildTool {
        MAVEN, GRADLE, UNKNOWN
    }
}
