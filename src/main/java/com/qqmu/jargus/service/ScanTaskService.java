package com.qqmu.jargus.service;

import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.IssueLevel;
import com.qqmu.jargus.entity.CiScanRecord;
import com.qqmu.jargus.entity.ReviewRule;
import com.qqmu.jargus.entity.ScanIssue;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.mapper.CiScanRecordMapper;
import com.qqmu.jargus.mapper.ScanIssueMapper;
import com.qqmu.jargus.mapper.ScanTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qqmu.jargus.env.ProjectInfo;
import com.qqmu.jargus.test.UnitTestRunner;
import com.qqmu.jargus.util.FileUtils;
import com.qqmu.jargus.util.IssuePoints;
import com.qqmu.jargus.util.PageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 扫描任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanTaskService {

    /** ZIP 炸弹熔断：条目数上限（普通源码工程数千文件已属大型） */
    private static final int MAX_UNZIP_ENTRIES = 20000;
    /** ZIP 炸弹熔断：解压后总字节上限（multipart 入口 100MB，高压缩比可放大数十倍） */
    private static final long MAX_UNZIP_BYTES = 512L * 1024 * 1024;

    private final ScanTaskMapper scanTaskMapper;
    private final ScanIssueMapper scanIssueMapper;
    private final CiScanRecordMapper ciScanRecordMapper;
    private final CodeParseService codeParseService;
    private final ProjectEnvService projectEnvService;
    private final UnitTestRunner unitTestRunner;
    private final IgnoreRuleService ignoreRuleService;
    private final ReviewRuleService reviewRuleService;
    private final CiTriggerService ciTriggerService;
    private final CiCallbackService ciCallbackService;
    private final MailNotifyService mailNotifyService;
    private final AiSuggestionService aiSuggestionService;
    private final ReportService reportService;
    /** 本地全量落库时把逐条 insert 并入一个事务（万级问题从数万次自动提交降为一次批量提交）。 */
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Value("${app.work-dir:./work}")
    private String workDir;

    /**
     * 创建扫描任务（从粘贴的代码）
     */
    public ScanTask createFromPaste(String code, String taskName, String projectName,
                                    boolean includeTestCode, boolean enableAiReview,
                                    boolean notifyEnabled, String notifyRecipientIds) {
        // 保存到文件
        Path taskDir = createTaskDirectory();
        Path javaFile = taskDir.resolve("PastedCode.java");
        try {
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("保存代码失败: " + e.getMessage(), e);
        }

        ScanTask task = new ScanTask();
        task.setTaskName(taskName != null ? taskName : "粘贴代码扫描");
        task.setProjectName(projectName);
        task.setSourceType("PASTE");
        task.setStatus("PENDING");
        task.setIncludeTestCode(includeTestCode);
        task.setEnableAiReview(enableAiReview);
        task.setNotifyEnabled(notifyEnabled);
        task.setNotifyRecipientIds(notifyEnabled ? notifyRecipientIds : null);
        task.setSkipUnitTest(true);
        task.setSnapshotPath(taskDir.toAbsolutePath().toString());
        task.setTotalFiles(0);
        task.setTotalLines(0);
        task.setBlockerCount(0);
        task.setCriticalCount(0);
        task.setMajorCount(0);
        task.setMinorCount(0);
        task.setInfoCount(0);
        task.setTotalIssues(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        scanTaskMapper.insert(task);
        return task;
    }

    /**
     * 创建扫描任务（从 ZIP 文件）
     */
    public ScanTask createFromZip(byte[] zipData, String taskName, String projectName,
                                  boolean includeTestCode, boolean enableAiReview,
                                  boolean skipUnitTest,
                                  boolean notifyEnabled, String notifyRecipientIds) {
        Path taskDir = createTaskDirectory();
        Path sourceDir = taskDir.resolve("src");

        try {
            Files.createDirectories(sourceDir);
            log.info("开始解压ZIP文件, 大小: {} 字节", zipData.length);
            int fileCount = unzip(zipData, sourceDir);
            log.info("解压完成, 共 {} 个文件", fileCount);
        } catch (IOException e) {
            log.error("解压文件失败", e);
            throw new RuntimeException("解压文件失败: " + e.getMessage(), e);
        }

        ScanTask task = new ScanTask();
        task.setTaskName(taskName != null ? taskName : "ZIP 代码扫描");
        task.setProjectName(projectName);
        task.setSourceType("ZIP");
        task.setStatus("PENDING");
        task.setIncludeTestCode(includeTestCode);
        task.setEnableAiReview(enableAiReview);
        task.setSkipUnitTest(skipUnitTest);
        task.setNotifyEnabled(notifyEnabled);
        task.setNotifyRecipientIds(notifyEnabled ? notifyRecipientIds : null);
        task.setSnapshotPath(sourceDir.toAbsolutePath().toString());
        task.setTotalFiles(0);
        task.setTotalLines(0);
        task.setBlockerCount(0);
        task.setCriticalCount(0);
        task.setMajorCount(0);
        task.setMinorCount(0);
        task.setInfoCount(0);
        task.setTotalIssues(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        scanTaskMapper.insert(task);
        return task;
    }

    /**
     * CI/Git 克隆场景：克隆目录本身就是现成源码树，直接拷进任务快照目录，
     * 跳过「压 zip → 整包读进堆 → 再解压」的往返（大仓库可省数百 MB 堆峰值与三次全量 IO）。
     * 拷贝完成后调用方负责删原克隆目录。
     */
    public ScanTask createFromLocalDirectory(Path existingSource, String taskName, String projectName,
                                             boolean includeTestCode, boolean enableAiReview,
                                             boolean skipUnitTest,
                                             boolean notifyEnabled, String notifyRecipientIds) {
        Path taskDir = createTaskDirectory();
        Path sourceDir = taskDir.resolve("src");
        try {
            Files.createDirectories(sourceDir);
            // copyDirectory 已跳过 .git/.svn/.hg 等 VCS 元数据
            copyDirectory(existingSource, sourceDir);
        } catch (IOException e) {
            log.error("拷贝克隆源码到快照目录失败", e);
            throw new RuntimeException("拷贝克隆源码失败: " + e.getMessage(), e);
        }

        ScanTask task = new ScanTask();
        task.setTaskName(taskName != null ? taskName : "CI 代码扫描");
        task.setProjectName(projectName);
        task.setSourceType("CI");
        task.setStatus("PENDING");
        task.setIncludeTestCode(includeTestCode);
        task.setEnableAiReview(enableAiReview);
        task.setSkipUnitTest(skipUnitTest);
        task.setNotifyEnabled(notifyEnabled);
        task.setNotifyRecipientIds(notifyEnabled ? notifyRecipientIds : null);
        task.setSnapshotPath(sourceDir.toAbsolutePath().toString());
        task.setTotalFiles(0);
        task.setTotalLines(0);
        task.setBlockerCount(0);
        task.setCriticalCount(0);
        task.setMajorCount(0);
        task.setMinorCount(0);
        task.setInfoCount(0);
        task.setTotalIssues(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        scanTaskMapper.insert(task);
        return task;
    }

    /** 递归拷目录（跳过不存在的源、保留文件属性；跳过 .git 等 VCS 元数据，大仓库可省上百 MB）。 */
    private void copyDirectory(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) {
            return;
        }
        try (var walk = Files.walk(src)) {
            walk.forEach(p -> {
                try {
                    // 跳过 .git 目录及其全部内容（relativize 段含 .git 即整个子树）
                    if (isVcsMetadata(src.relativize(p))) {
                        return;
                    }
                    // 跳过符号链接：git 仓库可携带指向本机任意文件的链接，跟随复制会把
                    // ~/.ssh/id_rsa 等读进快照，再随 AI 评审/上下文接口外泄
                    if (Files.isSymbolicLink(p)) {
                        log.warn("跳过快照中的符号链接: {}", p);
                        return;
                    }
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("拷贝文件失败: " + p, e);
                }
            });
        }
    }

    /** 相对路径中任一段是 .git/.svn/.hg 即视为 VCS 元数据。 */
    private boolean isVcsMetadata(Path rel) {
        for (Path seg : rel) {
            String s = seg.toString();
            if (s.equals(".git") || s.equals(".svn") || s.equals(".hg")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重新上传代码并扫描：替换既有任务的代码快照后重跑扫描。
     * 任务名称 / 项目名称 / 扫描选项保持不变，旧问题与旧报告缓存一并清掉。
     */
    public ScanTask rescanFromZip(Long taskId, byte[] zipData) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        if ("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus())) {
            throw new RuntimeException("任务正在排队或扫描中，请等待完成后再重新上传");
        }

        // 先解压到新目录，成功后再删旧快照：解压失败时不丢原有代码
        Path taskDir = createTaskDirectory();
        Path sourceDir = taskDir.resolve("src");
        try {
            Files.createDirectories(sourceDir);
            int fileCount = unzip(zipData, sourceDir);
            log.info("重新上传解压完成: taskId={}, 共 {} 个文件", taskId, fileCount);
        } catch (IOException e) {
            FileUtils.deleteRecursively(taskDir);
            throw new RuntimeException("解压文件失败: " + e.getMessage(), e);
        }

        deleteOldSnapshot(task.getSnapshotPath());

        clearResultsAndReset(taskId, sourceDir.toAbsolutePath().toString(), "ZIP");

        task.setSnapshotPath(sourceDir.toAbsolutePath().toString());
        task.setSourceType("ZIP");
        task.setStatus("PENDING");
        return task;
    }

    /**
     * 修改任务：任务名/项目名/扫描选项（不触发扫描，下次重跑时生效）。
     * PENDING/RUNNING 不可改；PASTE 任务跳过单测恒为 true（粘贴代码没有单测可跑，服务端强制）。
     */
    public ScanTask updateTask(Long taskId, String taskName, String projectName,
                               boolean skipUnitTest, boolean includeTestCode, boolean enableAiReview,
                               boolean notifyEnabled, String notifyRecipientIds) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        if ("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus())) {
            throw new RuntimeException("任务正在排队或扫描中，请等待完成后再修改");
        }
        if (taskName == null || taskName.trim().isEmpty()) {
            throw new RuntimeException("任务名称不能为空");
        }
        // 清空收件人时前端传空串 → 归一化为 null；通知关闭时收件人一并清空（对齐创建语义）
        String recipients = notifyEnabled ? blankToNull(notifyRecipientIds) : null;

        // updateById 跳 null 字段（project_name/notify_recipient_ids 无法清空），改用 UpdateWrapper 显式 set
        scanTaskMapper.update(null, new UpdateWrapper<ScanTask>()
                .eq("id", taskId)
                .set("task_name", taskName.trim())
                .set("project_name", blankToNull(projectName))
                .set("skip_unit_test", "PASTE".equals(task.getSourceType()) || skipUnitTest)
                .set("include_test_code", includeTestCode)
                .set("enable_ai_review", enableAiReview)
                .set("notify_enabled", notifyEnabled)
                .set("notify_recipient_ids", recipients)
                .set("updated_at", LocalDateTime.now()));

        ScanTask updated = scanTaskMapper.selectById(taskId);
        fillSnapshotExists(updated);
        return updated;
    }

    /**
     * 原地重跑：不重新上传代码，复用现有快照，清空旧结果后重新排队扫描，任务 id 与结果链接不变。
     */
    public ScanTask rerunInPlace(Long taskId) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        if ("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus())) {
            throw new RuntimeException("任务正在排队或扫描中，请等待完成后再重跑");
        }
        // 服务端复查快照仍在磁盘上（前端禁用按钮不可信，TOCTOU 兜底）
        if (task.getSnapshotPath() == null || task.getSnapshotPath().isEmpty()
                || !Files.exists(Paths.get(task.getSnapshotPath()))) {
            throw new RuntimeException("源文件已不存在，请改用「重新上传扫描」");
        }
        clearResultsAndReset(taskId, null, null);
        task.setStatus("PENDING");
        return task;
    }

    /**
     * 清空旧扫描结果并把任务重置回待扫描状态（重新上传扫描 / 原地重跑共用）。
     * newSnapshotPath 非 null 时同步更新快照路径与来源类型（重新上传场景）。
     */
    private void clearResultsAndReset(Long taskId, String newSnapshotPath, String newSourceType) {
        // 深度评审在途时先释放重入锁，否则重跑后扫描完成时的自动评审会被旧 running 标记拒绝
        aiSuggestionService.stopJob(taskId);
        // 旧问题与报告磁盘缓存必须清掉，否则与新扫描结果混在一起 / 下载到过期报告
        scanIssueMapper.delete(new QueryWrapper<ScanIssue>().eq("task_id", taskId));
        reportService.purgeReportCache(taskId);

        // updateById 会跳过 null 字段，重置类字段（error_message/started_at/completed_at 等）用 UpdateWrapper 显式置空
        UpdateWrapper<ScanTask> uw = new UpdateWrapper<ScanTask>()
                .eq("id", taskId)
                .set("status", "PENDING")
                .set("error_message", null)
                .set("started_at", null)
                .set("completed_at", null)
                .set("duration_seconds", null)
                .set("mail_status", null)
                .set("blocker_count", 0)
                .set("critical_count", 0)
                .set("major_count", 0)
                .set("minor_count", 0)
                .set("info_count", 0)
                .set("total_issues", 0)
                .set("ai_issue_count", 0)
                .set("total_files", 0)
                .set("total_lines", 0)
                .set("updated_at", LocalDateTime.now());
        if (newSnapshotPath != null) {
            uw.set("snapshot_path", newSnapshotPath);
        }
        if (newSourceType != null) {
            uw.set("source_type", newSourceType);
        }
        scanTaskMapper.update(null, uw);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /**
     * 删除扫描任务：问题、报告磁盘缓存、本地留存的代码快照、关联的 CI 扫描记录一并清除
     * （结果已不可查看的触发记录没有留存价值；触发失败等记录可在记录页用每行删除按钮单独清理）。
     * 只动本任务的快照目录，其他任务不受影响。
     */
    public void deleteTask(Long taskId) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }
        if ("PENDING".equals(task.getStatus()) || "RUNNING".equals(task.getStatus())) {
            throw new RuntimeException("任务正在排队或扫描中，请等待完成后再删除");
        }
        scanIssueMapper.delete(new QueryWrapper<ScanIssue>().eq("task_id", taskId));
        reportService.purgeReportCache(taskId);
        deleteOldSnapshot(task.getSnapshotPath());
        ciScanRecordMapper.delete(new QueryWrapper<CiScanRecord>().eq("task_id", taskId));
        scanTaskMapper.deleteById(taskId);
        log.info("扫描任务已删除: taskId={}", taskId);
    }

    /**
     * 删除旧代码快照。ZIP 任务的 snapshotPath 指向任务目录下的 src 子目录，需连任务目录一起删；
     * 仅允许删 work-dir/snapshots 下的目录，防误删外部路径。
     */
    private void deleteOldSnapshot(String snapshotPath) {
        if (snapshotPath == null || snapshotPath.isEmpty()) {
            return;
        }
        Path old = Paths.get(snapshotPath).toAbsolutePath().normalize();
        if (old.getFileName() != null && "src".equals(old.getFileName().toString())) {
            old = old.getParent();
        }
        Path snapshotsRoot = Paths.get(workDir, "snapshots").toAbsolutePath().normalize();
        if (old == null || !old.startsWith(snapshotsRoot)) {
            log.warn("旧快照路径不在 snapshots 目录下，跳过删除: {}", snapshotPath);
            return;
        }
        FileUtils.deleteRecursively(old);
    }

    /**
     * 异步执行扫描任务（受 scanTaskExecutor 有界线程池约束，队列满时抛 RejectedExecutionException）
     */
    @Async("scanTaskExecutor")
    public void executeScanAsync(Long taskId) {
        try {
            executeScan(taskId);
        } catch (Exception e) {
            log.error("扫描任务执行失败: taskId={}", taskId, e);
            updateTaskStatus(taskId, "FAILED", e.getMessage());
        }
        // 回写关联的 CI 扫描记录状态（executeScan 内部已吞异常并置 FAILED）
        ScanTask latest = scanTaskMapper.selectById(taskId);
        String status = latest != null && latest.getStatus() != null ? latest.getStatus() : "FAILED";
        // 建扫描时勾选「启用 AI 语义评审」：扫描成功后后台跑 AI 合并评审（逐文件增量落库、
        // 刷新统计），再链式深度评审（仅高中危），邮件等增强建议落库后才发（PDF 在发信瞬间
        // 生成，含 AI 发现与 AI 建议）；深度评审未启动时回退 AI 阶段结束即发。
        // fire-and-forget 不阻塞扫描池；CI 回写仍在 SUCCESS 时立即发出（commit status 不该等
        // 慢模型），AI 后补问题不回写 CI（同深度评审口径）
        boolean aiPhasePending = "SUCCESS".equals(status) && latest != null
                && Boolean.TRUE.equals(latest.getEnableAiReview());
        try {
            ciTriggerService.syncRecordByTaskId(taskId, status);
        } catch (Exception e) {
            log.warn("回写 CI 记录状态失败: taskId={}, err={}", taskId, e.getMessage());
        }
        // CI 回调：commit status + MR/PR 自动回评（非 CI 任务无记录，直接返回；内部吞异常）
        ciCallbackService.onScanCompleted(taskId, status);
        if (aiPhasePending) {
            // 邮件延迟到 AI 后台阶段结束后发（协调线程 finally 统一发，跳过/异常场景也发）
            startAiPhaseAsync(taskId, status);
        } else {
            // 邮件通知：任务开启 notifyEnabled 时发送报告邮件（内部吞异常，绝不影响扫描结果与 CI 回写）
            mailNotifyService.onScanCompleted(taskId, status);
        }
    }

    /**
     * 同步执行扫描任务
     */
    public void executeScan(Long taskId) {
        ScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        // 更新状态为运行中
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        scanTaskMapper.updateById(task);

        try {
            Path sourcePath = Paths.get(task.getSnapshotPath());

            // 1. 环境检测
            log.info("开始环境检测...");
            ProjectInfo projectInfo = projectEnvService.analyzeProject(sourcePath);
            task.setJdkVersion(projectInfo.getJdkVersion());
            task.setSpringBootVersion(projectInfo.getSpringBootVersion());
            task.setTotalFiles(projectInfo.getJavaFileCount());
            task.setTotalLines(projectInfo.getTotalLines());

            // 2. 执行单元测试（可选）
            if (!Boolean.TRUE.equals(task.getSkipUnitTest())
                    && projectInfo.getBuildTool() != null
                    && !"NONE".equals(projectInfo.getBuildTool())) {
                log.info("开始执行单元测试...");
                UnitTestRunner.TestResult testResult = unitTestRunner.runTests(sourcePath, 300);
                log.info("单元测试完成: {} 个测试, {} 失败",
                        testResult.getTestsRun(), testResult.getTestsFailed());
                // 测试结果可以存入数据库，这里简化处理
            } else {
                log.info("跳过单元测试");
            }

            // 3. 执行代码检查
            log.info("开始代码静态检查...");
            // 本地检查器同步跑完即 SUCCESS；AI 合并评审在扫描成功后由后台阶段增量补入
            List<CheckIssue> issues = codeParseService.scanAndCheck(
                    sourcePath,
                    Boolean.TRUE.equals(task.getIncludeTestCode()),
                    taskId,
                    task.getJdkVersion(),
                    task.getSpringBootVersion()
            );

            // 保存问题到数据库（同文件同规则的多个命中点合并为一条，自动应用忽略规则）。
            // 本地全量阶段：把逐条 insert 并入一个事务，避免万级问题时每条自动提交刷盘。
            String sourceRootStr = sourcePath.toAbsolutePath().toString();
            var txTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            List<ScanIssue> savedIssues = txTemplate.execute(status ->
                    saveIssues(taskId, issues, sourceRootStr));

            // 统计未忽略的问题（按合并后的记录数计）；AI 命中数同口径统计（合并条含任一 AI 命中即计入）
            int blockerCount = 0, criticalCount = 0, majorCount = 0, minorCount = 0, infoCount = 0;
            int aiIssueCount = 0;
            for (ScanIssue saved : savedIssues) {
                if (Boolean.TRUE.equals(saved.getIsIgnored())) {
                    continue;
                }
                if (Boolean.TRUE.equals(saved.getIsAiGenerated())) {
                    aiIssueCount++;
                }
                switch (saved.getIssueLevel()) {
                    case "BLOCKER" -> blockerCount++;
                    case "CRITICAL" -> criticalCount++;
                    case "MAJOR" -> majorCount++;
                    case "MINOR" -> minorCount++;
                    default -> infoCount++;
                }
            }

            // 更新任务状态
            task.setStatus("SUCCESS");
            task.setCompletedAt(LocalDateTime.now());
            task.setBlockerCount(blockerCount);
            task.setCriticalCount(criticalCount);
            task.setMajorCount(majorCount);
            task.setMinorCount(minorCount);
            task.setInfoCount(infoCount);
            task.setAiIssueCount(aiIssueCount);
            task.setTotalIssues(blockerCount + criticalCount + majorCount + minorCount + infoCount);
            task.setTotalFiles(countJavaFiles(sourcePath));
            task.setTotalLines(countLines(sourcePath));
            task.setDurationSeconds(Duration.between(
                    task.getStartedAt(), LocalDateTime.now()
            ).getSeconds());

            scanTaskMapper.updateById(task);

            log.info("扫描任务完成: taskId={}, 原始命中={}, 合并后问题={}",
                    taskId, issues.size(), savedIssues.size());

        } catch (Exception e) {
            log.error("扫描任务失败: taskId={}", taskId, e);
            updateTaskStatus(taskId, "FAILED", e.getMessage());
        }
    }

    /**
     * 扫描后 AI 评审后台阶段：协调线程不占扫描池槽位（同深度评审模式）。
     * 扫描已按本地检查结论 SUCCESS 并回写 CI；AI 结果逐文件增量落库、刷新统计，
     * 页面刷新即可看到 AI 发现增长。阶段结束后清报告缓存、链式触发深度评审；
     * 报告邮件等深度评审完成回调里发（PDF 含 AI 建议），深度评审未启动或阶段
     * 跳过/异常时回退 finally 立即发，任何路径都不漏发。
     */
    private void startAiPhaseAsync(Long taskId, String status) {
        Thread coordinator = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                ScanTask task = scanTaskMapper.selectById(taskId);
                if (task != null && task.getSnapshotPath() != null) {
                    Path sourceRoot = Paths.get(task.getSnapshotPath());
                    codeParseService.runAiReviewPhase(taskId, sourceRoot,
                            Boolean.TRUE.equals(task.getIncludeTestCode()),
                            task.getJdkVersion(), task.getSpringBootVersion(),
                            (file, issues) -> persistAiIssues(taskId, sourceRoot, issues));
                }
            } catch (Exception e) {
                log.warn("AI 评审后台阶段异常: taskId={}, err={}", taskId, e.getMessage());
            } finally {
                // 阶段期间打开过预览的任务会把不含 AI 发现的报告缓存下去，统一清掉
                reportService.purgeReportCache(taskId);
                log.info("AI 评审后台阶段结束: taskId={}, 耗时 {} ms",
                        taskId, System.currentTimeMillis() - start);
                // 邮件再等深度评审：PDF 在发信瞬间生成，等增强建议落库后报告才带「AI 建议」。
                // 深度评审未实际启动（未配厂商/无待增强/已在跑）时回退到此处立即发
                if (!chainDeepReview(taskId, status)) {
                    mailNotifyService.onScanCompleted(taskId, status);
                }
            }
        }, "ai-phase-" + taskId);
        coordinator.setDaemon(true);
        coordinator.start();
    }

    /** AI 阶段单文件结果落库并刷新任务统计；任务已被删除则静默丢弃 */
    private void persistAiIssues(Long taskId, Path sourceRoot, List<CheckIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }
        if (scanTaskMapper.selectById(taskId) == null) {
            return;
        }
        saveIssues(taskId, issues, sourceRoot.toAbsolutePath().toString());
        refreshTaskIssueStats(taskId);
    }

    /**
     * 按库中未忽略问题重算任务计数列（AI 增量落库后调用）。
     * 门禁评分由计数实时推导（QualityGateService），无需单独维护。
     */
    private void refreshTaskIssueStats(Long taskId) {
        // 两次 GROUP BY 取代全 CLOB 实体重读：问题上万条时省大量内存与 SQL 往返
        // 1) 按五级分组计数
        Map<String, Long> levelCounts = new HashMap<>();
        try {
            List<Map<String, Object>> rows = scanIssueMapper.selectMaps(
                    new QueryWrapper<ScanIssue>()
                            .select("issue_level AS lv", "COUNT(*) AS cnt")
                            .eq("task_id", taskId)
                            .eq("is_ignored", false)
                            .groupBy("issue_level"));
            for (Map<String, Object> row : rows) {
                String lv = null;
                long c = 0;
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (e.getKey() == null) continue;
                    switch (e.getKey().toLowerCase()) {
                        case "lv" -> lv = e.getValue() != null ? String.valueOf(e.getValue()) : null;
                        case "cnt" -> c = e.getValue() instanceof Number n ? n.longValue() : 0L;
                    }
                }
                if (lv != null) levelCounts.put(lv, c);
            }
        } catch (Exception e) {
            log.warn("重算任务 {} 等级分布失败: {}", taskId, e.getMessage());
        }
        // 2) AI 生成问题计数（is_ai_generated=true 单独一次 COUNT，不随五级分组）
        long ai = 0;
        try {
            Long aiCount = scanIssueMapper.selectCount(
                    new QueryWrapper<ScanIssue>()
                            .eq("task_id", taskId)
                            .eq("is_ignored", false)
                            .eq("is_ai_generated", true));
            ai = aiCount != null ? aiCount : 0L;
        } catch (Exception e) {
            log.warn("重算任务 {} AI 问题计数失败: {}", taskId, e.getMessage());
        }
        int blocker = levelCounts.getOrDefault("BLOCKER", 0L).intValue();
        int critical = levelCounts.getOrDefault("CRITICAL", 0L).intValue();
        int major = levelCounts.getOrDefault("MAJOR", 0L).intValue();
        int minor = levelCounts.getOrDefault("MINOR", 0L).intValue();
        int info = levelCounts.getOrDefault("INFO", 0L).intValue();
        ScanTask upd = new ScanTask();
        upd.setId(taskId);
        upd.setBlockerCount(blocker);
        upd.setCriticalCount(critical);
        upd.setMajorCount(major);
        upd.setMinorCount(minor);
        upd.setInfoCount(info);
        upd.setAiIssueCount((int) ai);
        upd.setTotalIssues(blocker + critical + major + minor + info);
        scanTaskMapper.updateById(upd);
    }

    /**
     * 链式触发深度评审：未增强过滤天然只捡新问题的中高危（含 AI 新报）。
     * 启动成功则把发邮件挂到深度评审完成回调（报告 PDF 含增强建议）；
     * 返回 false 表示未启动（调用方回退立即发邮件），回调随之摘除。
     */
    private boolean chainDeepReview(Long taskId, String status) {
        ScanTask t = scanTaskMapper.selectById(taskId);
        if (t == null || !Boolean.TRUE.equals(t.getEnableAiReview())
                || !aiSuggestionService.isAvailable()) {
            return false;
        }
        aiSuggestionService.onJobComplete(taskId,
                () -> mailNotifyService.onScanCompleted(taskId, status));
        try {
            AiSuggestionService.Progress progress =
                    aiSuggestionService.startDeepReview(taskId, "BLOCKER,CRITICAL,MAJOR");
            if (progress.isRunning()) {
                log.info("AI 评审后台阶段完成，已链式触发 AI 深度评审: taskId={}, 范围=BLOCKER/CRITICAL/MAJOR，邮件延迟至深度评审结束", taskId);
                return true;
            }
            // 无待增强问题：协调线程不跑，回调不会触发
            aiSuggestionService.removeJobHook(taskId);
            return false;
        } catch (Exception e) {
            aiSuggestionService.removeJobHook(taskId);
            log.warn("链式触发 AI 深度评审失败: taskId={}, err={}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * 保存问题列表。
     * 同一文件 + 同一规则码的多个命中点（如多个魔法数字）合并为一条记录，
     * 全部问题位置存进 linePoints，避免逐行/逐字面量刷出大量重复记录；
     * DUP_CODE_BLOCK 同样参与合并，各条的重复对象（位置二）以"其他位置"行保留在描述里。
     *
     * @return 实际落库（合并后）的问题记录
     */
    private List<ScanIssue> saveIssues(Long taskId, List<CheckIssue> issues, String sourceRoot) {
        List<ScanIssue> saved = new ArrayList<>();
        Map<String, ReviewRule> ruleMeta = loadRuleMeta();
        Map<String, List<CheckIssue>> groups = new LinkedHashMap<>();
        for (CheckIssue issue : issues) {
            groups.computeIfAbsent(issue.getFilePath() + "|" + issue.getRuleCode(),
                    k -> new ArrayList<>()).add(issue);
        }
        for (List<CheckIssue> group : groups.values()) {
            ReviewRule meta = ruleMeta.get(group.get(0).getRuleCode());
            // 规则总开关：停用规则的命中不落库（等同检查器未发射）
            if (meta != null && Boolean.FALSE.equals(meta.getIsEnabled())) {
                continue;
            }
            // 默认等级：定义了等级的规则行优先（opt-in 元数据，无规则行的码跟随检查器）
            if (meta != null) {
                applyDefaultLevel(group, meta.getDefaultLevel());
            }
            List<CheckIssue> active = new ArrayList<>();
            List<CheckIssue> ignoredOnes = new ArrayList<>();
            for (CheckIssue issue : group) {
                if (ignoreRuleService.shouldIgnore(issue, sourceRoot)) {
                    ignoredOnes.add(issue);
                } else {
                    active.add(issue);
                }
            }
            // 行级忽略规则可能只命中其中几个点：非忽略点优先单独成条；全部被忽略时才落一条忽略记录
            if (!active.isEmpty()) {
                saved.add(persistMergedIssue(taskId, active, false));
            } else {
                saved.add(persistMergedIssue(taskId, ignoredOnes, true));
            }
        }
        return saved;
    }

    /** 规则元数据按规则码索引（opt-in：无规则行的码跟随检查器默认） */
    private Map<String, ReviewRule> loadRuleMeta() {
        Map<String, ReviewRule> meta = new HashMap<>();
        for (ReviewRule rule : reviewRuleService.listAll()) {
            if (rule.getRuleCode() != null) {
                meta.put(rule.getRuleCode(), rule);
            }
        }
        return meta;
    }

    /** 等级覆盖：仅接受规范五级码（历史别名与非法值不生效）；severity 与 CheckIssue 构造器同源（5 - ordinal） */
    private void applyDefaultLevel(List<CheckIssue> group, String levelCode) {
        IssueLevel level = IssueLevel.fromCode(levelCode);
        if (levelCode == null || !level.getCode().equals(levelCode)) {
            return;
        }
        for (CheckIssue hit : group) {
            hit.setLevel(level);
            hit.setSeverity(5 - level.ordinal());
        }
    }

    /**
     * 把同文件同规则的一组原始命中合并成一条 scan_issue 记录并插入。
     */
    private ScanIssue persistMergedIssue(Long taskId, List<CheckIssue> hits, boolean ignored) {
        // 以行号最早的命中为代表，列号等元数据沿用它
        CheckIssue rep = pickEarliestHit(hits);
        List<int[]> points = IssuePoints.merge(collectRawPoints(hits));

        ScanIssue scanIssue = new ScanIssue();
        scanIssue.setTaskId(taskId);
        scanIssue.setFilePath(rep.getFilePath());
        scanIssue.setFileName(FileUtils.extractFileName(rep.getFilePath()));
        scanIssue.setLineStart(points.get(0)[0]);
        scanIssue.setLineEnd(points.get(points.size() - 1)[1]);
        scanIssue.setColumnStart(rep.getColumnStart());
        scanIssue.setColumnEnd(rep.getColumnEnd());
        scanIssue.setIssueLevel(rep.getLevel().getCode());
        scanIssue.setCheckerType(rep.getCheckerType().getCode());
        scanIssue.setCheckerName(rep.getCheckerName());
        scanIssue.setRuleCode(rep.getRuleCode());
        scanIssue.setTitle(rep.getTitle());
        scanIssue.setDescription(buildMergedDescription(rep, hits, points));
        scanIssue.setCodeSnippet(firstNonBlankText(hits, CheckIssue::getCodeSnippet));
        String suggestion = firstNonBlankText(hits, CheckIssue::getSuggestion);
        scanIssue.setSuggestion(suggestion != null ? suggestion
                : SuggestionCatalog.get(rep.getRuleCode()));
        scanIssue.setSeverity(maxSeverityOf(hits));
        scanIssue.setIsAiGenerated(anyAiOf(hits));
        scanIssue.setOccurrenceCount(hits.size());
        // 多点才写 linePoints（单点直接用 line_start/line_end，保持旧数据形态一致）
        if (hits.size() > 1) {
            scanIssue.setLinePoints(IssuePoints.toLists(points));
        }
        scanIssue.setIsIgnored(ignored);
        if (ignored) {
            scanIssue.setIgnoreType("RULE");
            scanIssue.setIgnoreReason("匹配忽略规则");
        }
        scanIssue.setCreatedAt(LocalDateTime.now());
        scanIssueMapper.insert(scanIssue);
        return scanIssue;
    }

    /** 代表命中：行号最早者（列号等元数据沿用它） */
    private CheckIssue pickEarliestHit(List<CheckIssue> hits) {
        CheckIssue rep = hits.get(0);
        for (CheckIssue h : hits) {
            if (h.getLineStart() < rep.getLineStart()) {
                rep = h;
            }
        }
        return rep;
    }

    /** 各命中的行区间（行号 1-based：个别检查器文件级问题可能漏设为 0，归到第 1 行避免合并后区间为空） */
    private List<int[]> collectRawPoints(List<CheckIssue> hits) {
        List<int[]> rawPoints = new ArrayList<>();
        for (CheckIssue h : hits) {
            int s = Math.max(1, h.getLineStart());
            int e = Math.max(s, h.getLineEnd());
            rawPoints.add(new int[]{s, e});
        }
        return rawPoints;
    }

    /** 合并后描述：单条沿用原文；多条追加 DUP"其他位置"行与本文件同类问题计数 */
    private String buildMergedDescription(@NonNull CheckIssue rep, @NonNull List<CheckIssue> hits,
                                          List<int[]> points) {
        if (hits.size() == 1) {
            return rep.getDescription();
        }
        StringBuilder desc = new StringBuilder(rep.getDescription());
        // DUP 合并时保留各条的重复对象：非代表条的"位置二"追加为"其他位置"行
        if ("DUP_CODE_BLOCK".equals(rep.getRuleCode())) {
            for (String partner : collectDupPartners(rep, hits)) {
                desc.append("\n  其他位置：").append(partner);
            }
        }
        desc.append("\n本文件同类问题共 ").append(hits.size())
                .append(" 处，涉及行号：").append(IssuePoints.format(points));
        return desc.toString();
    }

    /** 非代表条目的重复对象（去重且保持首次出现顺序） */
    private Set<String> collectDupPartners(CheckIssue rep, List<CheckIssue> hits) {
        Set<String> partners = new LinkedHashSet<>();
        for (CheckIssue h : hits) {
            if (h == rep) {
                continue;
            }
            String partner = IssueMergeService.extractPartnerLocation(h.getDescription());
            if (partner != null) {
                partners.add(partner);
            }
        }
        return partners;
    }

    /** 命中里的最高 severity（秩从 1 起） */
    private int maxSeverityOf(List<CheckIssue> hits) {
        int maxSeverity = 1;
        for (CheckIssue h : hits) {
            maxSeverity = Math.max(maxSeverity, h.getSeverity());
        }
        return maxSeverity;
    }

    /** 是否存在 AI 生成的命中 */
    private boolean anyAiOf(List<CheckIssue> hits) {
        boolean anyAi = false;
        for (CheckIssue h : hits) {
            anyAi |= h.isAiGenerated();
        }
        return anyAi;
    }

    /** 按给定访问器取命中里首个非空白文本（全空白返回 null） */
    private String firstNonBlankText(List<CheckIssue> hits, Function<CheckIssue, String> getter) {
        for (CheckIssue h : hits) {
            String text = getter.apply(h);
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    /**
     * 获取任务详情（附带快照是否仍在磁盘的现算标记）
     */
    public ScanTask getById(Long id) {
        ScanTask task = scanTaskMapper.selectById(id);
        fillSnapshotExists(task);
        return task;
    }

    /** 瞬态字段：快照目录是否仍在磁盘上（编辑弹窗据此展示源文件状态并决定能否原地重跑） */
    private void fillSnapshotExists(ScanTask task) {
        if (task == null) {
            return;
        }
        task.setSnapshotExists(task.getSnapshotPath() != null && !task.getSnapshotPath().isEmpty()
                && Files.exists(Paths.get(task.getSnapshotPath())));
    }

    /**
     * 分页获取任务列表
     */
    public IPage<ScanTask> listTasks(int pageNum, int pageSize, String keyword) {
        Page<ScanTask> page = new Page<>(PageUtils.pageNum(pageNum), PageUtils.pageSize(pageSize));
        QueryWrapper<ScanTask> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like("task_name", keyword).or().like("project_name", keyword);
        }
        wrapper.orderByDesc("created_at");
        return scanTaskMapper.selectPage(page, wrapper);
    }

    /**
     * 更新任务状态
     */
    public void updateTaskStatus(Long taskId, String status, String errorMessage) {
        ScanTask task = new ScanTask();
        task.setId(taskId);
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskMapper.updateById(task);
    }

    /**
     * 创建任务目录
     */
    private Path createTaskDirectory() {
        // 毫秒时间戳 + 随机后缀：多项目并发上传时同一毫秒也不会撞目录
        String dirName = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path workPath = Paths.get(workDir, "snapshots", dirName);
        try {
            Files.createDirectories(workPath);
            return workPath;
        } catch (IOException e) {
            throw new RuntimeException("创建工作目录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断是否为 macOS 压缩包元数据（__MACOSX 目录、._ 开头的 AppleDouble 文件、.DS_Store），
     * 这些条目不应出现在源码快照里
     */
    static boolean isMacJunkEntry(String entryName) {
        if (entryName == null) return true;
        for (String part : entryName.replace('\\', '/').split("/")) {
            if ("__MACOSX".equals(part) || ".DS_Store".equals(part) || part.startsWith("._")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解压 ZIP 文件
     * @return 解压的文件数（不含目录）
     */
    private int unzip(byte[] zipData, Path destDir) throws IOException {
        int count = 0;
        long totalBytes = 0;
        Path normDestDir = destDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // macOS 图形界面压缩会带 __MACOSX/ 目录和 AppleDouble（._开头）二进制元数据，
                // 它们不是源码且会以 .java 结尾，后续按 UTF-8 读取/解析会直接搞挂扫描
                if (isMacJunkEntry(entry.getName())) {
                    zis.closeEntry();
                    continue;
                }
                Path entryPath = destDir.resolve(entry.getName()).toAbsolutePath().normalize();
                // 防止路径穿越
                if (!entryPath.startsWith(normDestDir)) {
                    log.warn("跳过可疑路径（路径穿越防护）: {}", entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    // ZIP 炸弹熔断：条目数与解压总量双重上限
                    // （multipart 入口 100MB，高压缩比可放大数十倍）
                    if (count >= MAX_UNZIP_ENTRIES) {
                        throw new IOException("ZIP 条目数超过上限（" + MAX_UNZIP_ENTRIES + "）");
                    }
                    Files.createDirectories(entryPath.getParent());
                    totalBytes += Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    if (totalBytes > MAX_UNZIP_BYTES) {
                        throw new IOException("ZIP 解压总量超过上限（" + (MAX_UNZIP_BYTES / 1024 / 1024) + "MB）");
                    }
                    count++;
                }
                zis.closeEntry();
            }
        }
        return count;
    }

    private int countJavaFiles(Path dir) {
        return (int) codeParseService.findJavaFiles(dir, true).size();
    }

    private int countLines(Path dir) {
        List<Path> files = codeParseService.findJavaFiles(dir, true);
        int total = 0;
        for (Path file : files) {
            // Files.lines 的解码异常在终结操作时以 UncheckedIOException 抛出
            try (Stream<String> lines = Files.lines(file)) {
                total += (int) lines.count();
            } catch (IOException | UncheckedIOException ignored) {
                // 非 UTF-8/二进制文件跳过，不能让一个坏文件搞挂整个任务
            }
        }
        return total;
    }

}
