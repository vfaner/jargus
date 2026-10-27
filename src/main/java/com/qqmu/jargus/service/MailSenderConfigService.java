package com.qqmu.jargus.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qqmu.jargus.entity.MailSender;
import com.qqmu.jargus.mapper.MailSenderMapper;
import com.qqmu.jargus.util.CryptoUtil;
import com.qqmu.jargus.util.MaskUtils;
import com.qqmu.jargus.util.PageUtils;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 发件配置服务（SMTP 账号管理）
 *
 * 规则：
 * - 同一时间至多一条 isEnabled=true（启用新配置时服务层自动停用其他）；允许零启用。
 * - 密码 AES 加密落库；列表/详情返回掩码；更新时空值或含 **** 的掩码 = 保留原密码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailSenderConfigService {

    /** 邮箱格式（RFC 5322 简化版，域名至少一段点分）；发件邮箱与测试收件人共用 */
    private static final String EMAIL_PATTERN = "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$";

    private final MailSenderMapper mailSenderMapper;
    private final MessageSource messageSource;

    public IPage<MailSender> list(int pageNum, int pageSize, String keyword) {
        Page<MailSender> page = new Page<>(PageUtils.pageNum(pageNum), PageUtils.pageSize(pageSize));
        QueryWrapper<MailSender> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like("name", keyword)
                    .or().like("host", keyword)
                    .or().like("from_address", keyword));
        }
        wrapper.orderByDesc("id");
        IPage<MailSender> result = mailSenderMapper.selectPage(page, wrapper);
        result.getRecords().forEach(this::maskPassword);
        return result;
    }

    public MailSender getById(Long id) {
        MailSender sender = mailSenderMapper.selectById(id);
        if (sender != null) maskPassword(sender);
        return sender;
    }

    public MailSender create(MailSender sender) {
        validate(sender);
        sender.setCreatedAt(LocalDateTime.now());
        sender.setUpdatedAt(LocalDateTime.now());
        if (sender.getUseSsl() == null) sender.setUseSsl(true);
        if (sender.getUseStarttls() == null) sender.setUseStarttls(false);
        if (sender.getIsEnabled() == null) sender.setIsEnabled(false);
        if (sender.getPassword() != null && !sender.getPassword().isEmpty()) {
            sender.setPassword(CryptoUtil.encrypt(sender.getPassword()));
        } else {
            sender.setPassword(null);
        }
        if (Boolean.TRUE.equals(sender.getIsEnabled())) {
            disableOthers(null);
        }
        mailSenderMapper.insert(sender);
        return getById(sender.getId());
    }

    public boolean update(Long id, MailSender input) {
        MailSender exist = mailSenderMapper.selectById(id);
        if (exist == null) return false;
        validate(input);
        input.setId(id);
        input.setUpdatedAt(LocalDateTime.now());
        // 密码留空或为掩码 = 不修改（置 null，MyBatis-Plus 默认跳过 null 字段保留原密文）
        String pw = input.getPassword();
        if (pw == null || pw.isEmpty() || pw.contains("****")) {
            input.setPassword(null);
        } else {
            input.setPassword(CryptoUtil.encrypt(pw));
        }
        if (Boolean.TRUE.equals(input.getIsEnabled()) && !Boolean.TRUE.equals(exist.getIsEnabled())) {
            disableOthers(id);
        }
        return mailSenderMapper.updateById(input) > 0;
    }

    public boolean delete(Long id) {
        return mailSenderMapper.deleteById(id) > 0;
    }

    /** 切换启用状态：已启用则停用；否则独启用（自动停用其他） */
    public boolean toggle(Long id) {
        MailSender sender = mailSenderMapper.selectById(id);
        if (sender == null) return false;
        boolean enabling = !Boolean.TRUE.equals(sender.getIsEnabled());
        if (enabling) {
            disableOthers(id);
        }
        MailSender patch = new MailSender();
        patch.setId(id);
        patch.setIsEnabled(enabling);
        patch.setUpdatedAt(LocalDateTime.now());
        return mailSenderMapper.updateById(patch) > 0;
    }

    /** 取当前启用的发件配置（密码已解密）；无启用配置返回 null */
    public MailSender getEnabledSender() {
        // 分页取首行（各方言可移植；不能用 .last("LIMIT 1")，Oracle/SQLServer/DB2 不认）
        List<MailSender> rows = mailSenderMapper.selectPage(new Page<>(1, 1, false),
                new QueryWrapper<MailSender>().eq("is_enabled", true).orderByAsc("id")).getRecords();
        MailSender sender = rows.isEmpty() ? null : rows.get(0);
        if (sender == null) return null;
        if (sender.getPassword() != null && !sender.getPassword().isEmpty()) {
            sender.setPassword(CryptoUtil.decrypt(sender.getPassword()));
        }
        return sender;
    }

    /** 是否存在启用的发件配置（轻量计数，不解密密码；供前端探活通知开关可用性） */
    public boolean hasEnabledSender() {
        Long n = mailSenderMapper.selectCount(new QueryWrapper<MailSender>().eq("is_enabled", true));
        return n != null && n > 0;
    }

    /** 按配置构建 JavaMailSenderImpl（不依赖 spring.mail.* 全局属性） */
    public JavaMailSenderImpl buildMailSender(MailSender cfg) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(cfg.getHost());
        if (cfg.getPort() != null) impl.setPort(cfg.getPort());
        impl.setDefaultEncoding("UTF-8");
        // SMTP 认证账号即发件邮箱本身（QQ/163/Gmail 等惯例）；填了密码才发起认证，留空走无鉴权（开放中继/本地测试槽）
        boolean auth = cfg.getPassword() != null && !cfg.getPassword().isEmpty();
        if (auth) {
            impl.setUsername(cfg.getFromAddress());
            impl.setPassword(cfg.getPassword());
        }
        Properties props = impl.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", auth ? "true" : "false");
        // 超时封顶：扫描线程池仅 3 槽，防死 SMTP 长时间占用
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");
        if (Boolean.TRUE.equals(cfg.getUseSsl())) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.trust", cfg.getHost());
        } else if (Boolean.TRUE.equals(cfg.getUseStarttls())) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.ssl.trust", cfg.getHost());
        }
        return impl;
    }

    /** 发送测试邮件（纯文本，验证 SMTP 连通性与鉴权） */
    public void sendTest(Long senderId, String to) throws Exception {
        if (to == null || !to.matches(EMAIL_PATTERN)) throw new RuntimeException("收件邮箱格式不正确");
        MailSender cfg = mailSenderMapper.selectById(senderId);
        if (cfg == null) throw new RuntimeException("发件配置不存在");
        if (cfg.getPassword() != null && !cfg.getPassword().isEmpty()) {
            cfg.setPassword(CryptoUtil.decrypt(cfg.getPassword()));
        }
        JavaMailSenderImpl impl = buildMailSender(cfg);
        MimeMessage message = impl.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        String alias = cfg.getFromAlias() != null && !cfg.getFromAlias().isEmpty()
                ? cfg.getFromAlias() : cfg.getName();
        helper.setFrom(cfg.getFromAddress(), alias);
        helper.setTo(to);
        helper.setSubject(messageSource.getMessage("mail.subject.test", null, Locale.SIMPLIFIED_CHINESE));
        helper.setText(messageSource.getMessage("mail.body.testText",
                new Object[]{cfg.getName()}, Locale.SIMPLIFIED_CHINESE), false);
        impl.send(message);
        log.info("测试邮件发送成功: senderId={}, to={}", senderId, to);
    }

    private void validate(MailSender sender) {
        if (sender.getName() == null || sender.getName().isBlank()) {
            throw new RuntimeException("配置名称不能为空");
        }
        if (sender.getHost() == null || sender.getHost().isBlank()) {
            throw new RuntimeException("SMTP 主机不能为空");
        }
        if (sender.getPort() == null || sender.getPort() <= 0 || sender.getPort() > 65535) {
            throw new RuntimeException("SMTP 端口不合法");
        }
        if (sender.getFromAddress() == null || !sender.getFromAddress().matches(EMAIL_PATTERN)) {
            throw new RuntimeException("发件邮箱格式不正确");
        }
    }

    /** 停用除 exceptId 外的全部配置（exceptId 为 null 时停用所有） */
    private void disableOthers(Long exceptId) {
        QueryWrapper<MailSender> wrapper = new QueryWrapper<>();
        wrapper.eq("is_enabled", true);
        if (exceptId != null) wrapper.ne("id", exceptId);
        List<MailSender> others = mailSenderMapper.selectList(wrapper);
        for (MailSender other : others) {
            MailSender patch = new MailSender();
            patch.setId(other.getId());
            patch.setIsEnabled(false);
            patch.setUpdatedAt(LocalDateTime.now());
            mailSenderMapper.updateById(patch);
        }
    }

    /** 密码替换为掩码（前4 + **** + 后4） */
    private void maskPassword(MailSender sender) {
        sender.setPassword(MaskUtils.secret(sender.getPassword()));
    }

}
