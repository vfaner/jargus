package com.qqmu.jargus.service;

import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.entity.IgnoreRule;
import com.qqmu.jargus.mapper.IgnoreRuleMapper;
import com.qqmu.jargus.util.PageUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 忽略规则服务
 *
 * 支持四种忽略类型（优先级从高到低）：
 * 1. LINE_NUMBER - 精确到文件 + 行号
 * 2. FILE_PATH   - 精确文件路径
 * 3. FILE_PATTERN - glob 模式匹配文件
 * 4. RULE_CODE   - 按规则编码忽略
 *
 * 任一规则匹配则问题被忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IgnoreRuleService {

    private final IgnoreRuleMapper ignoreRuleMapper;

    /** 缓存已启用的规则（简化版，变更时刷新） */
    private volatile List<IgnoreRule> activeRules = List.of();
    private final ConcurrentHashMap<String, PathMatcher> matcherCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 表结构在 ApplicationReadyEvent 中初始化，空库时此处可能查不到表，忽略即可
        try {
            refreshCache();
        } catch (Exception e) {
            log.warn("忽略规则缓存预加载失败（表可能尚未初始化），将在启动后刷新: {}", e.getMessage());
            activeRules = List.of();
        }
    }

    /**
     * 刷新规则缓存
     */
    public void refreshCache() {
        try {
            List<IgnoreRule> rules = ignoreRuleMapper.selectList(
                    new QueryWrapper<IgnoreRule>().eq("is_enabled", true)
            );
            activeRules = rules;
            matcherCache.clear();
            log.info("忽略规则缓存已刷新，共 {} 条启用规则", rules.size());
        } catch (Exception e) {
            log.warn("刷新忽略规则缓存失败: {}", e.getMessage());
            activeRules = List.of();
        }
    }

    /**
     * 判定问题是否应被忽略
     *
     * @param issue      问题
     * @param sourceRoot 源码根目录（用于相对路径计算）
     * @return true 表示应忽略
     */
    public boolean shouldIgnore(CheckIssue issue, String sourceRoot) {
        if (activeRules.isEmpty()) return false;

        String filePath = issue.getFilePath();
        String ruleCode = issue.getRuleCode();
        int lineStart = issue.getLineStart();

        // 计算相对路径（相对于 sourceRoot）
        String relativePath = filePath;
        if (sourceRoot != null && filePath.startsWith(sourceRoot)) {
            relativePath = filePath.substring(sourceRoot.length());
            if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
                relativePath = relativePath.substring(1);
            }
        }

        for (IgnoreRule rule : activeRules) {
            if (matchesRule(rule, relativePath, filePath, ruleCode, lineStart)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定单个规则是否匹配
     */
    private boolean matchesRule(IgnoreRule rule, String relativePath, String absPath, String ruleCode, int lineStart) {
        String type = rule.getRuleType();
        if (type == null) return false;

        switch (type.toUpperCase()) {
            case "LINE_NUMBER":
                // 需同时匹配文件路径和行号
                if (rule.getFilePath() == null || rule.getLineNumber() == null) return false;
                return pathMatches(rule.getFilePath(), relativePath, absPath)
                        && rule.getLineNumber() == lineStart;

            case "FILE_PATH":
                if (rule.getFilePath() == null) return false;
                return pathMatches(rule.getFilePath(), relativePath, absPath);

            case "FILE_PATTERN":
                if (rule.getFilePattern() == null) return false;
                return patternMatches(rule.getFilePattern(), relativePath)
                        || patternMatches(rule.getFilePattern(), absPath);

            case "RULE_CODE":
                if (rule.getRuleCode() == null || ruleCode == null) return false;
                return rule.getRuleCode().equalsIgnoreCase(ruleCode);

            default:
                return false;
        }
    }

    /**
     * 文件路径精确匹配
     */
    private boolean pathMatches(String rulePath, String relativePath, String absPath) {
        if (rulePath == null || relativePath == null || absPath == null) return false;
        // 相对路径匹配或绝对路径匹配
        return rulePath.equals(relativePath)
                || rulePath.equals(absPath)
                || absPath.endsWith(rulePath)
                || relativePath.endsWith(rulePath);
    }

    /**
     * glob 模式匹配
     */
    private boolean patternMatches(String pattern, String path) {
        if (pattern == null || path == null) return false;
        try {
            PathMatcher matcher = matcherCache.computeIfAbsent(pattern, p ->
                    FileSystems.getDefault().getPathMatcher("glob:" + p)
            );
            // 用 Path 进行匹配
            return matcher.matches(Paths.get(path))
                    || matcher.matches(Paths.get(path.replace('\\', '/')));
        } catch (Exception e) {
            log.debug("glob 模式匹配失败: pattern={}, path={}, err={}", pattern, path, e.getMessage());
            return false;
        }
    }

    // ==================== CRUD ====================

    public IPage<IgnoreRule> list(int pageNum, int pageSize, String keyword) {
        Page<IgnoreRule> page = new Page<>(PageUtils.pageNum(pageNum), PageUtils.pageSize(pageSize));
        QueryWrapper<IgnoreRule> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like("rule_code", keyword)
                    .or().like("file_path", keyword)
                    .or().like("file_pattern", keyword)
                    .or().like("reason", keyword));
        }
        wrapper.orderByDesc("created_at");
        return ignoreRuleMapper.selectPage(page, wrapper);
    }

    public IgnoreRule getById(Long id) {
        return ignoreRuleMapper.selectById(id);
    }

    public IgnoreRule create(IgnoreRule rule) {
        if (rule.getIsEnabled() == null) rule.setIsEnabled(true);
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        ignoreRuleMapper.insert(rule);
        refreshCache();
        return rule;
    }

    public boolean update(IgnoreRule rule) {
        rule.setUpdatedAt(LocalDateTime.now());
        int rows = ignoreRuleMapper.updateById(rule);
        if (rows > 0) refreshCache();
        return rows > 0;
    }

    public boolean delete(Long id) {
        int rows = ignoreRuleMapper.deleteById(id);
        if (rows > 0) refreshCache();
        return rows > 0;
    }

    public boolean toggle(Long id) {
        IgnoreRule rule = ignoreRuleMapper.selectById(id);
        if (rule == null) return false;
        rule.setIsEnabled(!Boolean.TRUE.equals(rule.getIsEnabled()));
        rule.setUpdatedAt(LocalDateTime.now());
        int rows = ignoreRuleMapper.updateById(rule);
        if (rows > 0) refreshCache();
        return rows > 0;
    }
}
