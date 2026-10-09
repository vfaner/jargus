package com.qqmu.jargus.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * JavaParser AST 调用图构建器
 *
 * 基于源码 AST 分析构建调用图。
 * 精度不如 ASM 字节码分析，但不需要编译产物。
 *
 * 局限：
 * - 无法精确解析方法重载（参数类型推导不完整）
 * - lambda 体内的调用归属到宿主方法（可接受）；方法引用已收集（R47），
 *   但无法推导其函数式接口参数个数（描述符记为 (-1)V）
 * - 无法识别通过反射的调用
 */
@Slf4j
public class AstCallGraphBuilder {

    private final CallGraph callGraph;

    public AstCallGraphBuilder(CallGraph callGraph) {
        this.callGraph = callGraph;
    }

    /**
     * 分析单个编译单元
     *
     * @param cu         编译单元
     * @param filePath   文件路径
     * @param sourceRoot 源码根目录
     */
    public void analyzeCompilationUnit(CompilationUnit cu, String filePath, Path sourceRoot) {
        if (cu == null) return;

        // 获取包名
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("");

        // 分析所有类
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            String className = buildClassName(packageName, cls);

            // 收集方法定义（普通方法）
            cls.getMethods().forEach(method -> {
                boolean isPublic = method.isPublic();
                boolean isStatic = method.isStatic();
                boolean isAbstract = method.isAbstract();
                boolean isNative = method.isNative();

                // 构建描述符（简化版，只有参数类型名）
                String descriptor = buildSimpleDescriptor(method);

                MethodInfo methodInfo = MethodInfo.builder()
                        .className(className)
                        .methodName(method.getNameAsString())
                        .descriptor(descriptor)
                        .filePath(filePath)
                        .lineStart(method.getBegin().map(p -> p.line).orElse(0))
                        .lineEnd(method.getEnd().map(p -> p.line).orElse(0))
                        .isPublic(isPublic)
                        .isStatic(isStatic)
                        .isConstructor(false)
                        .isAbstract(isAbstract)
                        .isNative(isNative)
                        .isOverride(isOverride(method))
                        .annotations(annotationNames(method))
                        .build();

                callGraph.addMethod(methodInfo);

                // 分析方法内的调用
                analyzeMethodCalls(methodInfo, method);
            });

            // 收集构造方法
            cls.getConstructors().forEach(constructor -> {
                String descriptor = buildSimpleDescriptor(constructor);
                MethodInfo methodInfo = MethodInfo.builder()
                        .className(className)
                        .methodName(cls.getNameAsString())
                        .descriptor(descriptor)
                        .filePath(filePath)
                        .lineStart(constructor.getBegin().map(p -> p.line).orElse(0))
                        .lineEnd(constructor.getEnd().map(p -> p.line).orElse(0))
                        .isPublic(constructor.isPublic())
                        .isStatic(false)
                        .isConstructor(true)
                        .isAbstract(false)
                        .isNative(false)
                        .build();

                callGraph.addMethod(methodInfo);

                // 分析构造方法内的调用
                analyzeConstructorCalls(methodInfo, constructor);
            });
        });
    }

    /**
     * 分析方法内的调用
     */
    private void analyzeMethodCalls(MethodInfo caller, MethodDeclaration method) {
        method.findAll(MethodCallExpr.class).forEach(call -> {
            int line = call.getBegin().map(p -> p.line).orElse(0);
            String methodName = call.getNameAsString();

            // 尝试解析被调用者的类
            String calleeClass = resolveCalleeClass(call, caller.getClassName());

            // 简化描述符
            String descriptor = "(" + call.getArguments().size() + ")V";

            CallEdge edge = CallEdge.builder()
                    .caller(caller)
                    .calleeClassName(calleeClass)
                    .calleeMethodName(methodName)
                    .calleeDescriptor(descriptor)
                    .lineNumber(line)
                    .callType(CallEdge.CallType.VIRTUAL)
                    .build();

            callGraph.addCall(edge);
        });

        // 方法引用（Class::method / expr::method）同样构成使用（R47）
        method.findAll(MethodReferenceExpr.class).forEach(ref -> {
            int line = ref.getBegin().map(p -> p.line).orElse(0);
            String methodName = ref.getIdentifier();
            String calleeClass = resolveReferenceClass(ref, caller.getClassName());

            CallEdge edge = CallEdge.builder()
                    .caller(caller)
                    .calleeClassName(calleeClass)
                    .calleeMethodName(methodName)
                    // 方法引用无法推导实参个数，用 -1 标记（名称匹配仍生效）
                    .calleeDescriptor("(-1)V")
                    .lineNumber(line)
                    .callType(CallEdge.CallType.VIRTUAL)
                    .build();

            callGraph.addCall(edge);
        });

        // 也检查对象创建（构造方法调用）
        method.findAll(ObjectCreationExpr.class).forEach(creation -> {
            int line = creation.getBegin().map(p -> p.line).orElse(0);
            String typeName = creation.getType().asString();
            String descriptor = "(" + creation.getArguments().size() + ")V";

            CallEdge edge = CallEdge.builder()
                    .caller(caller)
                    .calleeClassName(typeName)
                    .calleeMethodName(typeName.contains(".") ? typeName.substring(typeName.lastIndexOf('.') + 1) : typeName)
                    .calleeDescriptor(descriptor)
                    .lineNumber(line)
                    .callType(CallEdge.CallType.SPECIAL)
                    .build();

            callGraph.addCall(edge);
        });
    }

    /**
     * 分析构造方法内的调用
     */
    private void analyzeConstructorCalls(MethodInfo caller, ConstructorDeclaration constructor) {
        constructor.findAll(MethodCallExpr.class).forEach(call -> {
            int line = call.getBegin().map(p -> p.line).orElse(0);
            String methodName = call.getNameAsString();
            String calleeClass = resolveCalleeClass(call, caller.getClassName());
            String descriptor = "(" + call.getArguments().size() + ")V";

            CallEdge edge = CallEdge.builder()
                    .caller(caller)
                    .calleeClassName(calleeClass)
                    .calleeMethodName(methodName)
                    .calleeDescriptor(descriptor)
                    .lineNumber(line)
                    .callType(CallEdge.CallType.VIRTUAL)
                    .build();

            callGraph.addCall(edge);
        });
    }

    /**
     * 解析被调用方法所属的类
     */
    private String resolveCalleeClass(MethodCallExpr call, String currentClass) {
        // 如果有 scope，尝试解析
        if (call.getScope().isPresent()) {
            var scope = call.getScope().get();
            // this.xxx 调用 -> 当前类
            if (scope.toString().equals("this") || scope.toString().equals("super")) {
                return currentClass;
            }
            // 类名.静态方法调用
            if (scope.toString().matches("[A-Z][a-zA-Z0-9]*")) {
                // 可能是简单类名，需要结合 import 解析（这里简化处理）
                return scope.toString();
            }
            // 其他情况，暂时标记为未知
            return "<scope>." + scope;
        }
        // 没有 scope，默认是当前类（this 省略）
        return currentClass;
    }

    /**
     * 解析方法引用的归属类：类型引用（CodeParseService::isNotMacJunk）取类型名，
     * 表达式引用（var::method）无法静态定位，标记为未解析
     */
    private String resolveReferenceClass(MethodReferenceExpr ref, String currentClass) {
        Expression scope = ref.getScope();
        if (scope instanceof TypeExpr typeExpr) {
            return typeExpr.getType().asString();
        }
        return "<scope>." + scope;
    }

    /**
     * 构建类的全限定名
     */
    private String buildClassName(String packageName, ClassOrInterfaceDeclaration cls) {
        String name = cls.getNameAsString();
        // 内部类处理
        cls.getParentNode().ifPresent(parent -> {
            // 简单处理：如果父节点也是类，则是内部类（这里暂不递归）
        });
        if (packageName.isEmpty()) {
            return name;
        }
        return packageName + "." + name;
    }

    /**
     * 构建简化版方法描述符（仅参数个数，用于匹配）
     */
    private String buildSimpleDescriptor(MethodDeclaration method) {
        int paramCount = method.getParameters().size();
        String returnType = method.getType().asString();
        return "(" + paramCount + ")" + returnType;
    }

    private String buildSimpleDescriptor(ConstructorDeclaration constructor) {
        int paramCount = constructor.getParameters().size();
        return "(" + paramCount + ")V";
    }

    /**
     * 判断是否是 override 方法
     */
    private boolean isOverride(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(a -> "Override".equals(a.getNameAsString())
                        || "java.lang.Override".equals(a.getNameAsString()));
    }

    /**
     * 收集方法注解的简单名（兼容全限定写法 @org.springframework...Scheduled）
     */
    private java.util.Set<String> annotationNames(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(a -> {
                    String name = a.getNameAsString();
                    int dot = name.lastIndexOf('.');
                    return dot >= 0 ? name.substring(dot + 1) : name;
                })
                .collect(java.util.stream.Collectors.toSet());
    }
}
