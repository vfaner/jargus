package com.qqmu.jargus.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qqmu.jargus.entity.MailRecipient;
import com.qqmu.jargus.mapper.MailRecipientMapper;
import com.qqmu.jargus.util.PageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知收件人服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailRecipientService {

    /** 邮箱格式（RFC 5322 简化版，域名至少一段点分），与发件配置校验保持一致 */
    private static final String EMAIL_PATTERN = "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$";

    private final MailRecipientMapper mailRecipientMapper;

    public IPage<MailRecipient> list(int pageNum, int pageSize, String keyword) {
        Page<MailRecipient> page = new Page<>(PageUtils.pageNum(pageNum), PageUtils.pageSize(pageSize));
        QueryWrapper<MailRecipient> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like("name", keyword).or().like("email", keyword));
        }
        wrapper.orderByDesc("id");
        return mailRecipientMapper.selectPage(page, wrapper);
    }

    /** 全量列表（下拉多选组件数据源） */
    public List<MailRecipient> listAll() {
        return mailRecipientMapper.selectList(new QueryWrapper<MailRecipient>().orderByAsc("name"));
    }

    public MailRecipient getById(Long id) {
        return mailRecipientMapper.selectById(id);
    }

    public MailRecipient create(MailRecipient recipient) {
        validate(recipient, null);
        recipient.setCreatedAt(LocalDateTime.now());
        recipient.setUpdatedAt(LocalDateTime.now());
        mailRecipientMapper.insert(recipient);
        return recipient;
    }

    public boolean update(Long id, MailRecipient input) {
        if (mailRecipientMapper.selectById(id) == null) return false;
        validate(input, id);
        input.setId(id);
        input.setUpdatedAt(LocalDateTime.now());
        return mailRecipientMapper.updateById(input) > 0;
    }

    public boolean delete(Long id) {
        return mailRecipientMapper.deleteById(id) > 0;
    }

    /**
     * 按逗号分隔 id 串解析收件人；保持 CSV 顺序，静默跳过已删除的 id
     */
    public List<MailRecipient> resolveByIds(String csv) {
        List<MailRecipient> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) return result;
        List<Long> ids = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                ids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                log.warn("收件人 id 串含非法值，已跳过: {}", trimmed);
            }
        }
        if (ids.isEmpty()) return result;
        List<MailRecipient> found = mailRecipientMapper.selectBatchIds(ids);
        for (Long id : ids) {
            for (MailRecipient r : found) {
                if (id.equals(r.getId())) {
                    result.add(r);
                    break;
                }
            }
        }
        return result;
    }

    private void validate(MailRecipient recipient, Long excludeId) {
        if (recipient.getName() == null || recipient.getName().isBlank()) {
            throw new RuntimeException("姓名不能为空");
        }
        String email = recipient.getEmail();
        if (email == null || !email.matches(EMAIL_PATTERN)) {
            throw new RuntimeException("邮箱格式不正确");
        }
        QueryWrapper<MailRecipient> wrapper = new QueryWrapper<>();
        wrapper.eq("email", email);
        if (excludeId != null) wrapper.ne("id", excludeId);
        Long dup = mailRecipientMapper.selectCount(wrapper);
        if (dup != null && dup > 0) {
            throw new RuntimeException("该邮箱已存在");
        }
    }
}
