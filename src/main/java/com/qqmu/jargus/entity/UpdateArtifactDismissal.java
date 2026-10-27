package com.qqmu.jargus.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 同版本号安装包重发提示的忽略记录（固定单行 id=1；无行=未忽略）。
 */
@Data
@TableName("update_artifact_dismissal")
public class UpdateArtifactDismissal {

    public static final long SINGLETON_ID = 1L;

    /** 固定主键，不能使用数据库自增。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** sha256:&lt;hex&gt; 或 size:&lt;bytes&gt;。 */
    private String artifactKey;

    private LocalDateTime dismissedAt;
}
