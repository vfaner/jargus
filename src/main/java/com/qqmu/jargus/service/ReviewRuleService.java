package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.ReviewRule;
import com.qqmu.jargus.mapper.ReviewRuleMapper;
import com.qqmu.jargus.util.PageUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评审规则服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewRuleService {

    private final ReviewRuleMapper reviewRuleMapper;

    public IPage<ReviewRule> list(int pageNum, int pageSize, String category, String keyword) {
        Page<ReviewRule> page = new Page<>(PageUtils.pageNum(pageNum), PageUtils.pageSize(pageSize));
        QueryWrapper<ReviewRule> wrapper = new QueryWrapper<>();
        if (category != null && !category.isEmpty() && !"ALL".equalsIgnoreCase(category)) {
            wrapper.eq("rule_category", category);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like("rule_code", keyword)
                    .or().like("rule_name", keyword)
                    .or().like("description", keyword));
        }
        wrapper.orderByAsc("sort_order");
        return reviewRuleMapper.selectPage(page, wrapper);
    }

    public List<ReviewRule> listAll() {
        return reviewRuleMapper.selectList(
                new QueryWrapper<ReviewRule>().orderByAsc("sort_order")
        );
    }

    public ReviewRule getById(Long id) {
        return reviewRuleMapper.selectById(id);
    }

    public ReviewRule getByCode(String ruleCode) {
        return reviewRuleMapper.selectOne(
                new QueryWrapper<ReviewRule>().eq("rule_code", ruleCode)
        );
    }

    public ReviewRule create(ReviewRule rule) {
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        if (rule.getIsEnabled() == null) rule.setIsEnabled(true);
        if (rule.getIsBuiltin() == null) rule.setIsBuiltin(false);
        if (rule.getDefaultLevel() == null) rule.setDefaultLevel("MAJOR");
        reviewRuleMapper.insert(rule);
        return rule;
    }

    public boolean update(ReviewRule rule) {
        rule.setUpdatedAt(LocalDateTime.now());
        return reviewRuleMapper.updateById(rule) > 0;
    }

    public boolean delete(Long id) {
        return reviewRuleMapper.deleteById(id) > 0;
    }

    public boolean toggle(Long id) {
        ReviewRule rule = reviewRuleMapper.selectById(id);
        if (rule == null) return false;
        rule.setIsEnabled(!Boolean.TRUE.equals(rule.getIsEnabled()));
        rule.setUpdatedAt(LocalDateTime.now());
        return reviewRuleMapper.updateById(rule) > 0;
    }
}
