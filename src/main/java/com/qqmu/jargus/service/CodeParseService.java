package com.qqmu.jargus.service;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerRegistry;
import com.qqmu.jargus.checker.CodeChecker;
import com.qqmu.jargus.checker.PostScanChecker;
import com.qqmu.jargus.llm.AiClientFactory;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 代码解析服务
 *
 * 负责：
 * - 扫描源码目录，找出所有 Java 文件
 * - 使用 JavaParser 解析 AST
 * - 调用检查器执行检查
 * - 汇总检查结果
 */
@Slf4j
@Service
public class CodeParseService {

    private final CheckerRegistry checkerRegistry;
    private final JavaParser javaParser;
    private final AiClientFactory aiClientFactory;
    /** 合并 AI 评审入口（每文件单次调用，见 checkFileAi） */
    private final AiReviewService aiReviewService;
    /** AI 评审专用线程池（LLM 调用 IO 密集，逐文件并发，与扫描池隔离） */
    private final Executor aiReviewExecutor;

    public CodeParseService(CheckerRegistry checkerRegistry,
                            AiClientFactory aiClientFactory,
                            AiReviewService aiReviewService,
                            @Qualifier("aiReviewExecutor") Executor aiReviewExecutor) {
        this.checkerRegistry = checkerRegistry;
        this.aiClientFactory = aiClientFactory;
        this.aiReviewService = aiReviewService;
        this.aiReviewExecutor = aiReviewExecutor;

        // 配置 JavaParser
        CombinedTypeSolver combinedSolver = new CombinedTypeSolver();
        combinedSolver.add(new ReflectionTypeSolver());

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedSolver);

        ParserConfiguration configuration = new ParserConfiguration();
        configuration.setSymbolResolver(symbolSolver);
        configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        configuration.setStoreTokens(true);
        // 不开 lexicalPreservation：本项目不做 AST 回写/格式化（无任何 LexicalPreservingPrinter
        // 调用），开启会让每个 token 记录原始文本、解析显著变慢，并使并发解析必须串行加锁。

        this.javaParser = new JavaParser(configuration);
    }

    /**
     * 扫描并检查指定目录下的所有 Java 文件
     *
     * @param sourceRoot     源码根目录
     * @param includeTest    是否包含测试代码
     * @param taskId         任务ID
     * @param jdkVersion     JDK 版本
     * @param springBootVersion Spring Boot 版本
     * @return 所有检查发现的问题
     */
    public List<CheckIssue> scanAndCheck(
            Path sourceRoot,
            boolean includeTest,
            Long taskId,
            String jdkVersion,
            String springBootVersion
    ) {
        List<CheckIssue> allIssues = Collections.synchronizedList(new ArrayList<>());

        List<Path> javaFiles = findJavaFiles(sourceRoot, includeTest);
        log.info("找到 {} 个 Java 文件", javaFiles.size());

        // 获取启用的本地检查器（AI 评审已移至扫描后后台阶段，见 runAiReviewPhase）
        List<CodeChecker> localCheckers = checkerRegistry.getEnabledLocalCheckers();
        log.info("启用的本地检查器: {} 个", localCheckers.size());

        // 全局上下文数据
        Map<String, Object> globalData = new HashMap<>();

        // 统计文件数和行数
        int[] stats = {0, 0};

        for (Path javaFile : javaFiles) {
            try {
                stats[0]++;
                // 逐文件循环只跑本地检查器：checkFile 的上下文 enableAiReview=false，
                // AI 检查器在此会被 accept() 拒绝，统一放到下面的 AI 阶段执行
                List<CheckIssue> fileIssues = checkFile(
                        sourceRoot,
                        javaFile,
                        localCheckers,
                        taskId,
                        jdkVersion,
                        springBootVersion,
                        includeTest,
                        globalData
                );
                allIssues.addAll(fileIssues);
                stats[1] += countLines(javaFile);
            } catch (Exception e) {
                log.warn("检查文件 {} 出错: {}", javaFile, e.getMessage());
            }
        }

        // 扫描级检查（跨文件汇总 / 依赖漏洞扫描）：文件循环结束后统一调用一次。
        // 即使没有任何 Java 文件也执行（依赖扫描只需 pom.xml）。
        CheckContext postCtx = CheckContext.builder()
                .taskId(taskId)
                .sourceRoot(sourceRoot)
                .jdkVersion(jdkVersion)
                .springBootVersion(springBootVersion)
                .includeTestCode(includeTest)
                .globalData(globalData)
                .build();
        for (CodeChecker checker : localCheckers) {
            if (checker instanceof PostScanChecker postScanChecker) {
                try {
                    allIssues.addAll(postScanChecker.postScanCheck(postCtx));
                } catch (Exception e) {
                    log.warn("检查器 {} 扫描级后处理出错: {}", checker.getName(), e.getMessage());
                }
            }
        }

        log.info("扫描完成: {} 个文件, {} 行代码, {} 个问题",
                stats[0], stats[1], allIssues.size());

        return allIssues;
    }

    /**
     * 扫描后 AI 评审阶段：逐文件合并调用（每文件单次 LLM 调用覆盖全部启用维度），
     * 每完成一个文件经 sink 增量交付（调用方在后台线程里落库/统计）。
     * 本方法阻塞到全部文件完成，供协调线程调用；并发走 aiReviewExecutor。
     */
    public void runAiReviewPhase(Long taskId,
                                 Path sourceRoot,
                                 boolean includeTest,
                                 String jdkVersion,
                                 String springBootVersion,
                                 BiConsumer<Path, List<CheckIssue>> sink) {
        if (!aiClientFactory.isAiConfigured()) {
            log.info("扫描后 AI 评审跳过: 未配置/启用 AI 厂商, taskId={}", taskId);
            return;
        }
        List<CodeChecker> aiCheckers = checkerRegistry.getEnabledAiCheckers();
        if (aiCheckers.isEmpty()) {
            log.info("扫描后 AI 评审跳过: 无启用的 AI 检查器, taskId={}", taskId);
            return;
        }
        List<Path> javaFiles = findJavaFiles(sourceRoot, includeTest);
        if (javaFiles.isEmpty()) {
            return;
        }
        log.info("开始 AI 评审阶段: taskId={}, {} 个文件, {} 个 AI 检查器",
                taskId, javaFiles.size(), aiCheckers.size());
        long start = System.currentTimeMillis();
        int total = javaFiles.size();
        AtomicInteger completed = new AtomicInteger();
        // 多个文件任务在 aiReviewExecutor 上并发共享该 Map，必须线程安全
        Map<String, Object> globalData = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Path javaFile : javaFiles) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    List<CheckIssue> issues = checkFileAi(sourceRoot, javaFile, aiCheckers, taskId,
                            jdkVersion, springBootVersion, includeTest, globalData);
                    sink.accept(javaFile, issues);
                } catch (Exception e) {
                    log.warn("AI 评审文件 {} 出错: {}", javaFile, e.getMessage());
                } finally {
                    // 进度可见性：LLM 调用动辄数秒，长扫描不能再是黑盒
                    int n = completed.incrementAndGet();
                    if (n % 10 == 0 || n == total) {
                        log.info("AI 评审进度: {}/{} 个文件", n, total);
                    }
                }
            }, aiReviewExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        log.info("AI 评审阶段完成: 耗时 {} ms", System.currentTimeMillis() - start);
    }

    /**
     * 对单个文件执行 AI 评审：与 checkFile 同构，但上下文 enableAiReview=true 放行 AI 检查器。
     * 检查器不再逐个执行（那意味着每文件 3 次 LLM 调用），而是 accept 过滤后汇总
     * 启用的评审维度，交给 AiReviewService.combinedReview 单次调用完成；
     * /checkers 页的维度开关依然生效——关掉哪个维度，合并提示词就不含哪个。
     */
    private List<CheckIssue> checkFileAi(Path sourceRoot,
                                         Path javaFile,
                                         List<CodeChecker> aiCheckers,
                                         Long taskId,
                                         String jdkVersion,
                                         String springBootVersion,
                                         boolean includeTest,
                                         Map<String, Object> globalData) {
        String relativePath = sourceRoot.relativize(javaFile).toString()
                .replace(File.separatorChar, '/');

        String sourceCode;
        List<String> sourceLines;
        try {
            sourceCode = Files.readString(javaFile, StandardCharsets.UTF_8);
            sourceLines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取文件失败: {}", javaFile);
            return Collections.emptyList();
        }

        // 尝试解析 AST（解析必须串行，原因同 checkFile）
        CompilationUnit cu = null;
        try {
            ParseResult<CompilationUnit> result;
            synchronized (javaParser) {
                result = javaParser.parse(sourceCode);
            }
            if (result.isSuccessful()) {
                cu = result.getResult().orElse(null);
            }
        } catch (Exception e) {
            log.debug("AI 阶段解析文件异常: {} - {}", relativePath, e.getMessage());
        }

        CheckContext context = CheckContext.builder()
                .taskId(taskId)
                .sourceRoot(sourceRoot)
                .currentFilePath(relativePath)
                .currentFileAbsolutePath(javaFile)
                .compilationUnit(cu)
                .sourceCode(sourceCode)
                .sourceLines(sourceLines)
                .jdkVersion(jdkVersion)
                .springBootVersion(springBootVersion)
                .includeTestCode(includeTest)
                .enableAiReview(true) // AI 阶段专属执行，放行 AI 检查器
                .globalData(globalData)
                .build();

        // accept 过滤（跳过非 Java / 超大文件）并汇总启用的评审维度
        List<String> enabled = new ArrayList<>();
        for (CodeChecker checker : aiCheckers) {
            try {
                if (!checker.accept(context)) {
                    continue;
                }
                String type = switch (checker.getCheckerType()) {
                    case AI_SEMANTIC -> "semantic";
                    case AI_SECURITY -> "security";
                    case AI_DESIGN -> "design";
                    default -> null;
                };
                if (type != null && !enabled.contains(type)) {
                    enabled.add(type);
                }
            } catch (Exception e) {
                log.warn("AI 检查器 {} 处理文件 {} 时出错: {}",
                        checker.getName(), relativePath, e.getMessage());
            }
        }
        if (enabled.isEmpty()) {
            return Collections.emptyList();
        }
        // 固定维度顺序，保证提示词稳定（注册表顺序不保证）
        List<String> reviewTypes = Stream.of("semantic", "security", "design")
                .filter(enabled::contains)
                .collect(Collectors.toList());
        try {
            return aiReviewService.combinedReview(context, reviewTypes);
        } catch (Exception e) {
            log.warn("AI 合并评审文件 {} 出错: {}", relativePath, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 检查单个文件
     */
    public List<CheckIssue> checkFile(
            Path sourceRoot,
            Path javaFile,
            List<CodeChecker> checkers,
            Long taskId,
            String jdkVersion,
            String springBootVersion,
            boolean includeTest,
            Map<String, Object> globalData
    ) {
        String relativePath = sourceRoot.relativize(javaFile).toString()
                .replace(File.separatorChar, '/');

        String sourceCode = "";
        List<String> sourceLines = Collections.emptyList();

        try {
            sourceCode = Files.readString(javaFile, StandardCharsets.UTF_8);
            sourceLines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取文件失败: {}", javaFile);
            return Collections.emptyList();
        }

        // 尝试解析 AST
        CompilationUnit cu = null;
        try {
            // JavaParser 单实例在开启 lexicalPreservation 时并发解析会竞态
            // （词法保存器共享状态，偶发 IndexOutOfBounds，导致整个文件 AST 丢失），解析必须串行
            ParseResult<CompilationUnit> result;
            synchronized (javaParser) {
                result = javaParser.parse(sourceCode);
            }
            if (result.isSuccessful()) {
                cu = result.getResult().orElse(null);
            } else {
                log.warn("解析文件失败: {} | {}", relativePath, result.getProblems());
            }
        } catch (Exception e) {
            log.debug("解析文件异常: {} - {}", relativePath, e.getMessage());
        }

        // 构建检查上下文
        CheckContext context = CheckContext.builder()
                .taskId(taskId)
                .sourceRoot(sourceRoot)
                .currentFilePath(relativePath)
                .currentFileAbsolutePath(javaFile)
                .compilationUnit(cu)
                .sourceCode(sourceCode)
                .sourceLines(sourceLines)
                .jdkVersion(jdkVersion)
                .springBootVersion(springBootVersion)
                .includeTestCode(includeTest)
                .enableAiReview(false) // AI 检查器由独立的 AI 评审阶段放行（见 runAiReviewPhase）
                .globalData(globalData)
                .build();

        List<CheckIssue> fileIssues = new ArrayList<>();

        for (CodeChecker checker : checkers) {
            try {
                if (checker.accept(context)) {
                    List<CheckIssue> issues = checker.check(context);
                    fileIssues.addAll(issues);
                }
            } catch (Exception e) {
                log.warn("检查器 {} 处理文件 {} 时出错: {}",
                        checker.getName(), relativePath, e.getMessage());
            }
        }

        return fileIssues;
    }

    /**
     * 查找目录下所有 Java 文件
     */
    public List<Path> findJavaFiles(Path sourceRoot, boolean includeTest) {
        if (!Files.exists(sourceRoot) || !Files.isDirectory(sourceRoot)) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    // 符号链接不参与扫描：跟随链接会把快照外的本机文件读入分析结果
                    .filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(CodeParseService::isNotMacJunk)
                    .filter(p -> includeTest || !isTestFile(sourceRoot, p))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("扫描文件出错: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 排除 macOS 压缩包元数据：__MACOSX 目录下的文件与 ._ 开头的 AppleDouble 文件。
     * 它们是二进制文件（恰好可能以 .java 结尾），按 UTF-8 读取会抛 MalformedInputException，
     * 交给 JavaParser 还可能触发内部 AssertionError。
     */
    static boolean isNotMacJunk(Path p) {
        for (Path seg : p) {
            String n = seg.toString();
            if ("__MACOSX".equals(n) || n.startsWith("._")) return false;
        }
        return true;
    }

    /**
     * 判断是否是测试文件
     */
    private boolean isTestFile(Path sourceRoot, Path file) {
        String relativePath = sourceRoot.relativize(file).toString()
                .replace(File.separatorChar, '/');
        return relativePath.contains("/test/")
                || relativePath.startsWith("test/")
                || relativePath.endsWith("Test.java")
                || relativePath.endsWith("Tests.java");
    }

    /**
     * 统计文件行数
     */
    private int countLines(Path file) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return (int) lines.count();
        } catch (IOException | UncheckedIOException e) {
            // 二进制/非 UTF-8 文件读不了，按 0 行处理
            return 0;
        }
    }
}
