package com.qqmu.jargus.controller;

import com.qqmu.jargus.security.PublicAccess;
import com.qqmu.jargus.security.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 所有 Thymeleaf 页面共享的模型属性。
 *
 * <p>REST 端点也会匹配本 advice，但 @ResponseBody 方法上模型属性不产生任何输出，
 * 因此无需排除。
 */
@PublicAccess
@ControllerAdvice
public class GlobalModelAdvice {

    @Value("${app.github-url:https://github.com/}")
    private String githubUrl;

    @Value("${app.auth-enabled:true}")
    private boolean authEnabled;

    @Value("${app.version:}")
    private String appVersion;

    /** 静态资源版本号：未单独配置时回落到应用版本号 */
    @Value("${app.asset-version:}")
    private String assetVersion;

    /** 关闭后 layout 不渲染导航更新圆点，settings 显示「版本检查已关闭」 */
    @Value("${app.update-check.enabled:true}")
    private boolean updateCheckEnabled;

    @ModelAttribute("appVersion")
    public String appVersion() {
        return (appVersion != null && !appVersion.isBlank()) ? "v" + appVersion.trim() : null;
    }

    @ModelAttribute("assetVersion")
    public String assetVersion() {
        String v = (assetVersion != null && !assetVersion.isBlank()) ? assetVersion.trim() : appVersion;
        return (v != null && !v.isBlank()) ? "v" + v.trim() : null;
    }

    /** 导航栏图标与打赏弹窗里的仓库链接 */
    @ModelAttribute("githubUrl")
    public String githubUrl() {
        return githubUrl;
    }

    @ModelAttribute("authEnabled")
    public boolean authEnabled() {
        return authEnabled;
    }

    @ModelAttribute("updateCheckEnabled")
    public boolean updateCheckEnabled() {
        return updateCheckEnabled;
    }

    @ModelAttribute("currentUsername")
    public String currentUsername() {
        UserContext.CurrentUser u = UserContext.get();
        return u == null ? null : u.getUsername();
    }

    @ModelAttribute("currentNickname")
    public String currentNickname() {
        UserContext.CurrentUser u = UserContext.get();
        return u == null ? null : u.getNickname();
    }

    @ModelAttribute("currentRole")
    public String currentRole() {
        UserContext.CurrentUser u = UserContext.get();
        return u == null ? null : u.getRole();
    }

    /** 模板里所有写操作控件都以此为准；后端 RoleAspect 仍然兜底鉴权 */
    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        return UserContext.isAdmin();
    }

    @ModelAttribute("currentSource")
    public String currentSource() {
        UserContext.CurrentUser u = UserContext.get();
        return u == null ? null : u.getSource();
    }

    @ModelAttribute("usingDefaultPassword")
    public boolean usingDefaultPassword() {
        UserContext.CurrentUser u = UserContext.get();
        return u != null && u.isPasswordDefault();
    }
}
