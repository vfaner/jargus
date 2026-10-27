package com.qqmu.jargus.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.security.RequiresRole;
import com.qqmu.jargus.service.version.VersionService;

import lombok.RequiredArgsConstructor;

/**
 * 检查更新接口：导航圆点与「同版本安装包重发」提示共用。
 *
 * <p>方法名启发式不认识 {@code check}，类级锁 VIEWER 保证登录用户可查；
 * 「忽略提示」会改变持久化状态，方法级再锁 ADMIN。
 * 离线或双源失败仍是正常响应，用 {@code checked=false} 表达，不返回 5xx。
 */
@RestController
@RequestMapping("/api/version")
@RequiredArgsConstructor
@RequiresRole("VIEWER")
public class VersionApiController {

    private final VersionService versionService;

    @GetMapping("/check")
    public Result<VersionService.UpdateCheck> check() {
        return Result.success(versionService.checkNow());
    }

    @PostMapping("/artifact/dismiss")
    @RequiresRole("ADMIN")
    public Result<Map<String, Object>> dismissArtifact() {
        return Result.success(Map.of("dismissed", versionService.dismissArtifact()));
    }
}
