package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * System.out / System.err 检查器
 *
 * 检测生产代码中使用 System.out.println 等输出语句，
 * 建议使用日志框架（SLF4J、Log4j 等）。
 */
@Component
public class SystemOutChecker extends AbstractLocalChecker {

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.CODE_STYLE;
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        // 测试代码不报告控制台输出：测试类通常无容器日志配置，打印断言辅助信息是正常做法
        if (isTestFile(context.getCurrentFilePath())) {
            return;
        }
        cu.findAll(MethodCallExpr.class).forEach(methodCall -> {
            // main 方法等 CLI 入口里的 System.out 是与终端交互的正常手段，不是漏用日志框架
            if (isInMainMethod(methodCall)) {
                return;
            }
            // 检查 System.out.println / print 等
            if (isSystemOutCall(methodCall) || isSystemErrCall(methodCall)) {
                int line = methodCall.getBegin().map(p -> p.line).orElse(1);
                String methodName = methodCall.getNameAsString();

                issues.add(createIssue(
                        IssueLevel.MAJOR,
                        "SYSTEM_OUT",
                        "使用 System.out/err 输出",
                        "生产代码建议使用日志框架（如 SLF4J）替代 System." + getStreamName(methodCall) + "." + methodName + "()，便于日志级别控制和持久化",
                        context.getCurrentFilePath(),
                        line,
                        line
                ));
            }
        });
    }

    /**
     * 判断调用是否位于 main 方法（public static void main(String[])）内
     */
    private boolean isInMainMethod(MethodCallExpr methodCall) {
        return methodCall.findAncestor(MethodDeclaration.class)
                .map(md -> "main".equals(md.getNameAsString())
                        && md.isStatic()
                        && md.isPublic()
                        && md.getParameters().size() == 1)
                .orElse(false);
    }

    /**
     * 判断是否是测试代码文件（src/test 目录或 *Test/*Tests/*TestCase/*IT 命名）
     */
    private boolean isTestFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        String normalized = filePath.replace('\\', '/');
        if (normalized.contains("/src/test/") || normalized.contains("/test/")) {
            return true;
        }
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return fileName.endsWith("Test.java") || fileName.endsWith("Tests.java")
                || fileName.endsWith("TestCase.java") || fileName.endsWith("IT.java");
    }

    /**
     * 判断是否是 System.out 的方法调用
     */
    private boolean isSystemOutCall(MethodCallExpr methodCall) {
        return methodCall.getScope().isPresent()
                && methodCall.getScope().get() instanceof FieldAccessExpr fieldAccess
                && isSystemOut(fieldAccess);
    }

    /**
     * 判断是否是 System.err 的方法调用
     */
    private boolean isSystemErrCall(MethodCallExpr methodCall) {
        return methodCall.getScope().isPresent()
                && methodCall.getScope().get() instanceof FieldAccessExpr fieldAccess
                && isSystemErr(fieldAccess);
    }

    private boolean isSystemOut(FieldAccessExpr fieldAccess) {
        return "out".equals(fieldAccess.getNameAsString())
                && fieldAccess.getScope() instanceof NameExpr nameExpr
                && "System".equals(nameExpr.getNameAsString());
    }

    private boolean isSystemErr(FieldAccessExpr fieldAccess) {
        return "err".equals(fieldAccess.getNameAsString())
                && fieldAccess.getScope() instanceof NameExpr nameExpr
                && "System".equals(nameExpr.getNameAsString());
    }

    private String getStreamName(MethodCallExpr methodCall) {
        if (methodCall.getScope().isPresent()
                && methodCall.getScope().get() instanceof FieldAccessExpr fieldAccess) {
            return fieldAccess.getNameAsString();
        }
        return "out";
    }
}
