package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.QualityGateResult;
import com.qqmu.jargus.entity.ScanTask;
import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.env.ProjectInfo;
import com.qqmu.jargus.security.PublicAccess;
import com.qqmu.jargus.security.UserContext;
import com.qqmu.jargus.service.CheckerConfigService;
import com.qqmu.jargus.service.CiTokenService;
import com.qqmu.jargus.service.CiTriggerService;
import com.qqmu.jargus.service.DatabaseConfigService;
import com.qqmu.jargus.service.IgnoreRuleService;
import com.qqmu.jargus.service.LlmTemplateService;
import com.qqmu.jargus.service.MailRecipientService;
import com.qqmu.jargus.service.MailSenderConfigService;
import com.qqmu.jargus.service.ProjectEnvService;
import com.qqmu.jargus.service.ProviderConfigService;
import com.qqmu.jargus.service.QualityGateService;
import com.qqmu.jargus.service.RemoteAuthConfigService;
import com.qqmu.jargus.service.ReviewRuleService;
import com.qqmu.jargus.service.ScanTaskService;
import com.qqmu.jargus.service.SsoConfigService;
import com.qqmu.jargus.service.SsoLoginPageResolver;
import com.qqmu.jargus.service.version.VersionService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thymeleaf 页面路由。
 *
 * <p>读取数据直接调用 Service（与 REST 控制器同源），写操作仍走 /api JSON。
 * 类上标注 {@link PublicAccess} 跳过 RoleAspect 的方法名启发式判断；
 * 登录态由 JwtAuthFilter 保证（未登录会被 302 到 /login），
 * 管理员专属页面在这里再做一次角色校验。
 */
@Slf4j
@PublicAccess
@Controller
@RequiredArgsConstructor
public class PageController {

    private final ScanTaskService scanTaskService;
    private final com.qqmu.jargus.mapper.ScanTaskMapper scanTaskMapper;
    private final QualityGateService qualityGateService;
    private final ProjectEnvService projectEnvService;
    private final CheckerConfigService checkerConfigService;
    private final ProviderConfigService providerConfigService;
    private final LlmTemplateService llmTemplateService;
    private final DatabaseConfigService databaseConfigService;
    private final IgnoreRuleService ignoreRuleService;
    private final ReviewRuleService reviewRuleService;
    private final CiTriggerService ciTriggerService;
    private final CiTokenService ciTokenService;
    private final RemoteAuthConfigService remoteAuthConfigService;
    private final MailSenderConfigService mailSenderConfigService;
    private final MailRecipientService mailRecipientService;
    private final VersionService versionService;
    private final SsoConfigService ssoConfigService;
    private final MessageSource messageSource;

    @Value("${app.work-dir:./work}")
    private String workDir;
    @Value("${app.driver-dir:./lib/custom}")
    private String driverDir;
    @Value("${app.gitee-url:}")
    private String giteeUrl;
    @Value("${app.website-url:}")
    private String websiteUrl;
    @Value("${app.contact.qq:}")
    private String contactQq;
    @Value("${app.contact.wechat:}")
    private String contactWechat;
    @Value("${app.contact.qq-group:}")
    private String contactQqGroup;

    /** 非管理员直敲管理员页面地址时，回到仪表盘 */
    private static final String ADMIN_REDIRECT = "redirect:/dashboard";

    private boolean admin() {
        return UserContext.isAdmin();
    }

    // ── 根路径 / 登录 ─────────────────────────────────────────

    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login(Model model, @RequestParam Map<String, String> params) {
        SsoConfig ssoConfig = ssoConfigService.getRaw();
        boolean localLoginEnabled = SsoConfigService.isLocalLoginEnabled(ssoConfig);
        boolean hasRemoteConfigs;
        try {
            hasRemoteConfigs = !remoteAuthConfigService.listEnabled().isEmpty();
        } catch (Exception e) {
            hasRemoteConfigs = false;
        }
        SsoLoginPageResolver.LoginPageState state = SsoLoginPageResolver.resolve(
                ssoConfig, localLoginEnabled, hasRemoteConfigs, params);
        if (state.redirectToSso()) {
            return "redirect:" + state.ssoEntryUrl();
        }
        model.addAttribute("ssoEnabled", state.ssoEnabled());
        model.addAttribute("ssoMode", state.ssoMode());
        model.addAttribute("ssoEntryUrl", state.ssoEntryUrl());
        model.addAttribute("showSsoButton", state.showSsoButton());
        model.addAttribute("showLocalForm", state.showLocalForm());
        model.addAttribute("showRemoteTab", state.showRemoteTab());
        model.addAttribute("showHeaderNotice", state.showHeaderNotice());
        String errorText = null;
        if (state.ssoErrorKey() != null) {
            try {
                errorText = messageSource.getMessage(state.ssoErrorKey(), null, LocaleContextHolder.getLocale());
            } catch (Exception ignored) {
                errorText = null;
            }
        }
        model.addAttribute("ssoErrorText", errorText);
        return "login";
    }

    // ── 仪表盘 ────────────────────────────────────────────────

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        IPage<ScanTask> recent = scanTaskService.listTasks(1, 8, null);

        // 等级总数走一次 SQL 聚合，不再拉 200 行实体进 Java 循环加总
        long blockers = 0, criticals = 0, majors = 0, minors = 0, infos = 0;
        try {
            java.util.Map<String, Object> sums = scanTaskMapper.selectMaps(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ScanTask>()
                            .select("COALESCE(SUM(blocker_count),0) AS b",
                                    "COALESCE(SUM(critical_count),0) AS c",
                                    "COALESCE(SUM(major_count),0) AS m",
                                    "COALESCE(SUM(minor_count),0) AS mn",
                                    "COALESCE(SUM(info_count),0) AS i")
            ).get(0);
            blockers = ((Number) sums.getOrDefault("b", 0)).longValue();
            criticals = ((Number) sums.getOrDefault("c", 0)).longValue();
            majors = ((Number) sums.getOrDefault("m", 0)).longValue();
            minors = ((Number) sums.getOrDefault("mn", 0)).longValue();
            infos = ((Number) sums.getOrDefault("i", 0)).longValue();
        } catch (Exception e) {
            // 聚合失败不挡仪表盘，退化为 0 展示
        }

        // 近 14 天趋势按天 GROUP BY，不拉全量任务
        Map<String, Long> trend = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = 13; i >= 0; i--) {
            trend.put(today.minusDays(i).toString(), 0L);
        }
        try {
            List<java.util.Map<String, Object>> rows = scanTaskMapper.selectMaps(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ScanTask>()
                            .select("DATE(created_at) AS day", "COUNT(*) AS cnt")
                            .ge("created_at", today.minusDays(13).atStartOfDay())
                            .groupBy("DATE(created_at)")
            );
            for (java.util.Map<String, Object> row : rows) {
                String day = null;
                long cnt = 0;
                for (java.util.Map.Entry<String, Object> e : row.entrySet()) {
                    if (e.getKey() == null) continue;
                    switch (e.getKey().toLowerCase()) {
                        case "day" -> day = e.getValue() != null ? String.valueOf(e.getValue()) : null;
                        case "cnt" -> cnt = e.getValue() instanceof Number n ? n.longValue() : 0L;
                    }
                }
                if (day != null && trend.containsKey(day)) {
                    trend.put(day, cnt);
                }
            }
        } catch (Exception e) {
            // 趋势失败退化为 0
        }
        long trendMax = trend.values().stream().mapToLong(Long::longValue).max().orElse(1);

        model.addAttribute("recent", recent.getRecords());
        model.addAttribute("taskTotal", recent.getTotal());
        model.addAttribute("blockers", blockers);
        model.addAttribute("criticals", criticals);
        model.addAttribute("majors", majors);
        model.addAttribute("minors", minors);
        model.addAttribute("infos", infos);
        model.addAttribute("trend", trend);
        model.addAttribute("trendMax", Math.max(1, trendMax));
        return "dashboard";
    }

    // ── 扫描 ──────────────────────────────────────────────────

    @GetMapping("/scan/new")
    public String scanNew() {
        return admin() ? "scan-new" : ADMIN_REDIRECT;
    }

    @GetMapping("/scan/result/{id}")
    public String scanResult(@PathVariable Long id, Model model) {
        ScanTask task = scanTaskService.getById(id);
        if (task == null) {
            return "redirect:/history";
        }
        QualityGateResult quality = null;
        if ("SUCCESS".equals(task.getStatus())) {
            try {
                quality = qualityGateService.evaluateTask(id);
            } catch (Exception ignored) {
                // 任务可能尚未写完统计数据，页面按缺省处理
            }
        }
        ProjectInfo env = null;
        if (task.getSnapshotPath() != null && !task.getSnapshotPath().isBlank()) {
            try {
                env = projectEnvService.analyzeProject(Paths.get(task.getSnapshotPath()));
            } catch (Throwable ignored) {
                // 快照目录缺失（如容器切换挂载）或个别文件解析异常都不应挡住结果页
                // （JavaParser 对二进制文件可能抛 AssertionError，属于 Error 不是 Exception）
            }
        }
        model.addAttribute("task", task);
        model.addAttribute("quality", quality);
        model.addAttribute("env", env);
        // 环境卡只展示真正参与运行的依赖（过滤 parent BOM 与 test 作用域）
        if (env != null && env.getDependencies() != null) {
            model.addAttribute("runtimeDeps", env.getDependencies().stream()
                    .filter(d -> !"parent".equals(d.getScope()) && !"test".equals(d.getScope()))
                    .toList());
        }
        if ("SUCCESS".equals(task.getStatus())) {
            model.addAttribute("gateSettings", qualityGateService.getGateSettings());
        }
        return "scan-result";
    }

    // ── 历史 ──────────────────────────────────────────────────

    @GetMapping("/history")
    public String history(@RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int size,
                          @RequestParam(required = false) String keyword,
                          Model model) {
        IPage<ScanTask> result = scanTaskService.listTasks(Math.max(1, page), size, keyword);
        model.addAttribute("pageData", result);
        model.addAttribute("keyword", keyword);
        model.addAttribute("baseQuery", keyword != null && !keyword.isBlank()
                ? "?keyword=" + keyword + "&" : "?");
        return "history";
    }

    // ── 检查器 ────────────────────────────────────────────────

    @GetMapping("/checkers")
    public String checkers(Model model) {
        model.addAttribute("checkers", checkerConfigService.listAll());
        return "checkers";
    }

    // ── 质量门禁 ──────────────────────────────────────────────

    @GetMapping("/quality-gate")
    public String qualityGate(Model model) {
        IPage<ScanTask> recent = scanTaskService.listTasks(1, 10, null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScanTask t : recent.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", t);
            if ("SUCCESS".equals(t.getStatus())) {
                try {
                    row.put("quality", qualityGateService.evaluateTask(t.getId()));
                } catch (Exception ignored) {
                    // 该行不显示评级
                }
            }
            rows.add(row);
        }
        model.addAttribute("rows", rows);
        model.addAttribute("gateSettings", qualityGateService.getGateSettings());
        return "quality-gate";
    }

    // ── 管理员：AI 厂商 ───────────────────────────────────────

    @GetMapping("/providers")
    public String providers(Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        model.addAttribute("providers", providerConfigService.listAll());
        model.addAttribute("templates", llmTemplateService.listBuiltin());
        return "providers";
    }

    // ── 管理员：数据库 ────────────────────────────────────────

    @GetMapping("/databases")
    public String databases(Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        model.addAttribute("databases", databaseConfigService.listAll());
        model.addAttribute("dbTypes", databaseConfigService.getDatabaseTypes());
        return "databases";
    }

    // ── 管理员：忽略规则 ──────────────────────────────────────

    @GetMapping("/ignore-rules")
    public String ignoreRules(@RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "10") int size,
                              @RequestParam(required = false) String keyword,
                              Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        IPage<?> result = ignoreRuleService.list(Math.max(1, page), size, keyword);
        model.addAttribute("pageData", result);
        model.addAttribute("keyword", keyword);
        model.addAttribute("baseQuery", keyword != null && !keyword.isBlank()
                ? "?keyword=" + keyword + "&" : "?");
        return "ignore-rules";
    }

    // ── 管理员：评审规则 ──────────────────────────────────────

    @GetMapping("/review-rules")
    public String reviewRules(@RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size,
                              @RequestParam(required = false) String category,
                              @RequestParam(required = false) String keyword,
                              Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        IPage<?> result = reviewRuleService.list(Math.max(1, page), size, category, keyword);
        model.addAttribute("pageData", result);
        model.addAttribute("category", category);
        model.addAttribute("keyword", keyword);
        StringBuilder q = new StringBuilder("?");
        if (category != null && !category.isBlank()) q.append("category=").append(category).append("&");
        if (keyword != null && !keyword.isBlank()) q.append("keyword=").append(keyword).append("&");
        model.addAttribute("baseQuery", q.toString());
        return "review-rules";
    }

    // ── 管理员：邮件管理 ──────────────────────────────────────

    @GetMapping("/mail/senders")
    public String mailSenders(Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        model.addAttribute("senders", mailSenderConfigService.list(1, 100, null).getRecords());
        return "mail-senders";
    }

    @GetMapping("/mail/recipients")
    public String mailRecipients(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "10") int size,
                                 @RequestParam(required = false) String keyword,
                                 Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        IPage<?> result = mailRecipientService.list(Math.max(1, page), size, keyword);
        model.addAttribute("pageData", result);
        model.addAttribute("keyword", keyword);
        model.addAttribute("baseQuery", keyword != null && !keyword.isBlank()
                ? "?keyword=" + keyword + "&" : "?");
        return "mail-recipients";
    }

    // ── 管理员：CI/CD ─────────────────────────────────────────

    @GetMapping("/ci")
    public String ci(Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        var triggerList = ciTriggerService.list(1, 100, null).getRecords();
        model.addAttribute("triggers", triggerList);
        model.addAttribute("tokens", ciTokenService.list(1, 100, null).getRecords());
        model.addAttribute("records", ciTriggerService.listRecords(1, 50, null).getRecords());
        // 记录按触发器筛选/展示名称用
        Map<Long, String> triggerNames = new HashMap<>();
        for (var t : triggerList) {
            triggerNames.put(t.getId(), t.getConfigName());
        }
        model.addAttribute("triggerNames", triggerNames);
        return "ci";
    }

    // ── 管理员：远端认证 ──────────────────────────────────────

    @GetMapping("/remote-auth")
    public String remoteAuth(Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        model.addAttribute("configs", remoteAuthConfigService.listAll());
        return "remote-auth";
    }

    // ── 管理员：系统设置 ──────────────────────────────────────

    @GetMapping("/settings")
    public String settings(Model model) {
        if (!admin()) return ADMIN_REDIRECT;
        model.addAttribute("activeDb", databaseConfigService.getActive());
        model.addAttribute("activeProvider", providerConfigService.getActive());
        model.addAttribute("checkerCount", checkerConfigService.listAll().size());
        model.addAttribute("taskCount", scanTaskService.listTasks(1, 1, null).getTotal());
        model.addAttribute("workDir", workDir);
        model.addAttribute("driverDir", driverDir);
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("osName", System.getProperty("os.name"));
        model.addAttribute("now", LocalDateTime.now());
        model.addAttribute("versionInfo", versionService.snapshot());
        model.addAttribute("giteeUrl", giteeUrl);
        model.addAttribute("websiteUrl", websiteUrl);
        model.addAttribute("contactQq", contactQq);
        model.addAttribute("contactWechat", contactWechat);
        model.addAttribute("contactQqGroup", contactQqGroup);
        return "settings";
    }
}
