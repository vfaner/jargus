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
 * <p>读取失败（表未迁移好、数据库暂时不可用）只降级为「未忽略」，不能让设置页 500；
 * 写入失败必须向上抛，由 {@link VersionService} 返回 {@code dismissed=false}，
 * 否则界面会假成功、重启后提示又出现。
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
        if (mapper.updateById(row) == 0) {
            // 行被并发删除时不能静默成功，否则提示会在下次刷新后复现
            throw new IllegalStateException("安装包忽略记录不存在或已被并发删除");
        }
    }
}
