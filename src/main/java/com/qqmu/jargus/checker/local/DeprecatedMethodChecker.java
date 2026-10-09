package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 废弃方法检测检查器
 *
 * 检测调用了 @Deprecated 注解标记的方法或类。
 * 包括：
 * - 调用了废弃方法
 * - 继承了废弃类
 * - 使用了废弃字段
 */
@Component
public class DeprecatedMethodChecker extends AbstractLocalChecker {

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.DEPRECATED_METHOD;
    }

    @Override
    public int getPriority() {
        return 40;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        // 检查方法调用
        cu.findAll(MethodCallExpr.class).forEach(methodCall -> {
            // 这里只能检查语法层面的信息，无法解析符号
            // 实际的废弃方法检测需要结合符号解析或字节码分析
            // 这里先做简单的检测：如果方法调用在同一个 CompilationUnit 内且目标方法有 @Deprecated
            checkLocalDeprecatedCall(context, methodCall, issues);
        });

        // 检查类是否有 @Deprecated 注解（在本文件内声明）
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(type -> {
            if (isDeprecated(type)) {
                int line = type.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "DEPRECATED_CLASS",
                        "类被标记为废弃",
                        "类 '" + type.getNameAsString() + "' 使用了 @Deprecated 注解，标记为已废弃",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });

        // 检查方法是否有 @Deprecated 注解（在本文件内声明）
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            if (isDeprecated(method)) {
                int line = method.getBegin().map(p -> p.line).orElse(1);
                issues.add(createIssue(
                        IssueLevel.MINOR,
                        "DEPRECATED_METHOD_DECL",
                        "方法被标记为废弃",
                        "方法 '" + method.getNameAsString() + "' 使用了 @Deprecated 注解，标记为已废弃",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });
    }

    /**
     * 检查是否调用了同一个类内被标记为废弃的方法。
     *
     * 只匹配无 scope 或 this/super scope 的调用，且只在调用点所在类声明的方法里查找：
     * 无符号解析时纯名字匹配会把其它对象的同名调用一并误伤
     * （如本地废弃的 close() 误报 stream.close()）。
     */
    private void checkLocalDeprecatedCall(
            CheckContext context,
            MethodCallExpr methodCall,
            List<CheckIssue> issues
    ) {
        // 带 scope 的调用只认 this/super；其它 scope（obj.close()/Util.close()）无法确定归属类
        if (methodCall.getScope().isPresent()) {
            String scope = methodCall.getScope().get().toString();
            if (!"this".equals(scope) && !"super".equals(scope)) {
                return;
            }
        }

        // 废弃方法内部调用废弃方法不报告：旧 API 内部的既定迁移路径，报了也无法行动
        MethodDeclaration enclosingMethod = methodCall.findAncestor(MethodDeclaration.class).orElse(null);
        if (enclosingMethod != null && (isDeprecated(enclosingMethod) || suppressesDeprecation(enclosingMethod))) {
            return;
        }

        ClassOrInterfaceDeclaration enclosingClass =
                methodCall.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (enclosingClass == null || suppressesDeprecation(enclosingClass)) {
            return;
        }

        String methodName = methodCall.getNameAsString();
        // 命中多个废弃重载也只报一次，避免同一调用点重复噪音
        boolean hit = enclosingClass.getMethods().stream()
                .anyMatch(method -> method.getNameAsString().equals(methodName) && isDeprecated(method));
        if (!hit) {
            return;
        }
        int line = methodCall.getBegin().map(p -> p.line).orElse(1);
        issues.add(createIssue(
                IssueLevel.MINOR,
                "DEPRECATED_METHOD_CALL",
                "调用了废弃方法",
                "调用的方法 '" + methodName + "' 已被标记为 @Deprecated，建议使用替代方案",
                context.getCurrentFilePath(),
                line,
                line
        ));
    }

    /**
     * 判断节点是否有 @Deprecated 注解
     */
    private boolean isDeprecated(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .anyMatch(anno -> {
                    String name = anno.getNameAsString();
                    return "Deprecated".equals(name) || "java.lang.Deprecated".equals(name);
                });
    }

    /**
     * 判断节点是否带 @SuppressWarnings 且包含 "deprecation"（如兼容适配类整体豁免）
     */
    private boolean suppressesDeprecation(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .anyMatch(anno -> {
                    String name = anno.getNameAsString();
                    return ("SuppressWarnings".equals(name) || "java.lang.SuppressWarnings".equals(name))
                            && anno.toString().contains("deprecation");
                });
    }
}
