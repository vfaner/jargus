package com.qqmu.jargus.service.version;

import java.time.LocalDateTime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.qqmu.jargus.entity.UpdateArtifactDismissal;
import com.qqmu.jargus.mapper.UpdateArtifactDismissalMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * MyBatis-Plus 持久化：固定单行 id=1，先查后插/更（3.5.5 的 BaseMapper 没有 insertOrUpdate）。
 *
 * <p>表未迁移好或数据库暂时不可用时只降级为「未忽略」，不能让设置页 500。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DatabaseArtifactDismissal implements ArtifactDismissal {

    private final UpdateArtifactDismissalMapper mapper;

    @Override
    public String dismissedKey() {
        try {
            UpdateArtifactDismissal row =
                    mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID);
            return row == null ? null : row.getArtifactKey();
        } catch (RuntimeException e) {
            log.debug("读取安装包忽略记录失败: {}", e.toString());
            return null;
        }
    }

    @Override
    public void dismiss(String artifactKey) {
        if (!StringUtils.hasText(artifactKey)) {
            return;
        }
        String key = artifactKey.trim();
        try {
            UpdateArtifactDismissal row =
                    mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID);
            if (row == null) {
                row = new UpdateArtifactDismissal();
                row.setId(UpdateArtifactDismissal.SINGLETON_ID);
                row.setArtifactKey(key);
                row.setDismissedAt(LocalDateTime.now());
                try {
                    mapper.insert(row);
                    return;
                } catch (DuplicateKeyException race) {
                    // 两个管理员同时点忽略：后到者改为更新已有行
                    row = mapper.selectById(UpdateArtifactDismissal.SINGLETON_ID);
                    if (row == null) {
                        throw race;
                    }
                }
            }
            row.setArtifactKey(key);
            row.setDismissedAt(LocalDateTime.now());
            mapper.updateById(row);
        } catch (RuntimeException e) {
            log.warn("保存安装包忽略记录失败: {}", e.getMessage());
        }
    }
}
