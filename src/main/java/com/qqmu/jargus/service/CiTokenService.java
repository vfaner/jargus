package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.CiToken;
import com.qqmu.jargus.mapper.CiTokenMapper;
import com.qqmu.jargus.util.CryptoUtil;
import com.qqmu.jargus.util.PageUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * CI 令牌服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CiTokenService {

    private final CiTokenMapper ciTokenMapper;

    private static final SecureRandom random = new SecureRandom();

    public IPage<CiToken> list(int pageNum, int pageSize, String keyword) {
        Page<CiToken> page = new Page<>(PageUtils.pageNum(pageNum), PageUtils.pageSize(pageSize));
        QueryWrapper<CiToken> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like("token_name", keyword)
                    .or().like("description", keyword);
        }
        wrapper.orderByDesc("created_at");
        IPage<CiToken> result = ciTokenMapper.selectPage(page, wrapper);
        // 不返回 token_value 明文
        result.getRecords().forEach(t -> t.setTokenValue(maskToken(t.getTokenValue())));
        return result;
    }

    public CiToken getById(Long id) {
        CiToken token = ciTokenMapper.selectById(id);
        if (token != null) {
            token.setTokenValue(maskToken(token.getTokenValue()));
        }
        return token;
    }

    /**
     * 创建令牌，返回明文 token（只返回一次）
     */
    public String create(CiToken token) {
        // 生成随机 token
        String plainToken = generateToken();
        // 加密存储
        token.setTokenValue(CryptoUtil.encrypt(plainToken));
        token.setIsEnabled(token.getIsEnabled() != null ? token.getIsEnabled() : true);
        token.setCreatedAt(LocalDateTime.now());
        ciTokenMapper.insert(token);
        return plainToken;
    }

    public boolean update(Long id, CiToken token) {
        token.setId(id);
        token.setTokenValue(null);  // 不允许修改 token 值
        return ciTokenMapper.updateById(token) > 0;
    }

    public boolean delete(Long id) {
        return ciTokenMapper.deleteById(id) > 0;
    }

    public boolean toggle(Long id) {
        CiToken token = ciTokenMapper.selectById(id);
        if (token == null) return false;
        token.setIsEnabled(!Boolean.TRUE.equals(token.getIsEnabled()));
        return ciTokenMapper.updateById(token) > 0;
    }

    /**
     * 验证 token 是否有效
     */
    public boolean validateToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isEmpty()) return false;

        QueryWrapper<CiToken> wrapper = new QueryWrapper<>();
        wrapper.eq("is_enabled", true);
        var tokens = ciTokenMapper.selectList(wrapper);

        for (CiToken t : tokens) {
            try {
                String decrypted = CryptoUtil.decrypt(t.getTokenValue());
                if (decrypted != null && MessageDigest.isEqual(
                        decrypted.getBytes(StandardCharsets.UTF_8),
                        tokenValue.getBytes(StandardCharsets.UTF_8))) {
                    // 过期 token 直接拒绝，不再刷新使用时间
                    if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(LocalDateTime.now())) {
                        return false;
                    }
                    // 更新最后使用时间
                    t.setLastUsedAt(LocalDateTime.now());
                    ciTokenMapper.updateById(t);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 生成随机 token（32 字节 base64）
     */
    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "cicr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 掩码显示 token
     */
    private String maskToken(String encrypted) {
        if (encrypted == null) return null;
        // 只显示前 8 个字符
        String plain;
        try {
            plain = CryptoUtil.decrypt(encrypted);
        } catch (Exception e) {
            return "********";
        }
        if (plain.length() <= 12) return plain.substring(0, 4) + "****";
        return plain.substring(0, 8) + "****" + plain.substring(plain.length() - 4);
    }
}
