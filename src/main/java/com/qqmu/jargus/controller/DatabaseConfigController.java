package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.entity.DatabaseConfig;
import com.qqmu.jargus.service.DatabaseConfigService;
import com.qqmu.jargus.service.DatabaseSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据库配置控制器
 */
@RestController
@RequestMapping("/api/databases")
@RequiredArgsConstructor
public class DatabaseConfigController {

    private final DatabaseConfigService databaseConfigService;
    private final DatabaseSwitchService databaseSwitchService;

    @Value("${app.driver-dir:./lib/custom}")
    private String driverDir;

    /**
     * 获取所有数据库配置
     */
    @GetMapping
    public Result<List<DatabaseConfig>> list() {
        return Result.success(databaseConfigService.listAll());
    }

    /**
     * 根据ID获取配置
     */
    @GetMapping("/{id}")
    public Result<DatabaseConfig> getById(@PathVariable Long id) {
        return Result.success(databaseConfigService.getById(id));
    }

    /**
     * 获取激活的数据库
     */
    @GetMapping("/active")
    public Result<DatabaseConfig> getActive() {
        // 与 list/getById 一致脱敏：本端点对 VIEWER 放行，密文密码也不应离开服务端
        // （叠加默认加密密钥可知时，密文即明文）
        DatabaseConfig cfg = databaseConfigService.getActive();
        if (cfg != null) {
            cfg.setPassword(null);
        }
        return Result.success(cfg);
    }

    /**
     * 获取支持的数据库类型
     */
    @GetMapping("/types")
    public Result<List<Map<String, String>>> getDatabaseTypes() {
        return Result.success(databaseConfigService.getDatabaseTypes());
    }

    /**
     * 新增数据库配置
     */
    @PostMapping
    public Result<DatabaseConfig> create(@RequestBody DatabaseConfig config) {
        return Result.success(databaseConfigService.create(config));
    }

    /**
     * 更新数据库配置
     */
    @PutMapping("/{id}")
    public Result<DatabaseConfig> update(@PathVariable Long id, @RequestBody DatabaseConfig config) {
        return Result.success(databaseConfigService.update(id, config));
    }

    /**
     * 删除数据库配置
     */
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(databaseConfigService.delete(id));
    }

    /**
     * 测试连接
     */
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testConnection(@PathVariable Long id) {
        return Result.success(databaseConfigService.testConnection(id));
    }

    /**
     * 测试连接（直接传配置）
     */
    @PostMapping("/test")
    public Result<Map<String, Object>> testConnectionWithConfig(@RequestBody DatabaseConfig config) {
        return Result.success(databaseConfigService.testConnection(config));
    }

    /**
     * 检查目标数据库状态
     */
    @PostMapping("/{id}/check")
    public Result<Map<String, Object>> checkTarget(@PathVariable Long id) {
        return Result.success(databaseSwitchService.checkTargetDatabase(id));
    }

    /**
     * 激活/切换数据库
     */
    @PostMapping("/{id}/activate")
    public Result<Map<String, Object>> activate(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean initIfNeeded
    ) {
        return Result.success(databaseSwitchService.switchDatabase(id, initIfNeeded));
    }

    /**
     * 上传驱动 JAR
     */
    @PostMapping("/upload-driver")
    public Result<Map<String, String>> uploadDriver(@RequestParam("file") MultipartFile file) {
        try {
            File dir = new File(driverDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String uuid = UUID.randomUUID().toString().replace("-", "");
            String originalFileName = file.getOriginalFilename();
            // 仅接受 .jar 驱动文件；落盘文件名固定 uuid.jar（原始文件名不参与路径拼接，杜绝路径穿越）
            if (originalFileName == null || !originalFileName.toLowerCase().endsWith(".jar")) {
                return Result.error("仅支持上传 .jar 驱动文件");
            }
            String newFileName = uuid + ".jar";
            Path targetPath = Path.of(driverDir, newFileName);

            // 输入流必须显式关闭：Files.copy 不会替调用方关闭，上传大量驱动时会泄漏文件句柄
            try (var in = file.getInputStream()) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            return Result.success(Map.of(
                    "fileName", newFileName,
                    "filePath", targetPath.toAbsolutePath().toString(),
                    "originalName", originalFileName != null ? originalFileName : ""
            ));
        } catch (Exception e) {
            return Result.error("上传驱动失败: " + e.getMessage());
        }
    }
}
