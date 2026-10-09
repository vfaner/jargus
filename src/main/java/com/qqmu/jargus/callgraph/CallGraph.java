package com.qqmu.jargus.callgraph;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调用图
 *
 * 维护方法定义和调用关系
 */
@Slf4j
public class CallGraph {

    /**
     * 框架通过反射/代理调用的方法注解（简单名）：带这些注解的方法即使源码里
     * 找不到调用点也不是死代码——调度器、事件总线、容器生命周期、序列化框架、
     * AOP 通知、测试运行器都会在运行时调用它们。用于死代码误报豁免。
     */
    private static final Set<String> FRAMEWORK_INVOKED_ANNOTATIONS = Set.of(
            // 容器生命周期 / 调度 / 事件
            "PostConstruct", "PreDestroy", "Scheduled", "EventListener",
            "TransactionalEventListener", "KafkaListener", "RabbitListener", "JmsListener",
            "Subscribe", "Bean",
            // Spring MVC / 绑定
            "ExceptionHandler", "InitBinder", "ModelAttribute",
            // AspectJ 通知
            "Around", "Before", "After", "AfterReturning", "AfterThrowing",
            // 测试运行器
            "Test", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll",
            "ParameterizedTest", "RepeatedTest", "TestFactory",
            // Jackson 序列化回调
            "JsonCreator", "JsonProperty", "JsonGetter", "JsonSetter",
            "JsonAnyGetter", "JsonAnySetter", "JsonValue");

    /** 所有已定义的方法（按签名索引） */
    private final Map<String, MethodInfo> definedMethods = new ConcurrentHashMap<>();

    /** 所有调用边 */
    private final List<CallEdge> callEdges = new ArrayList<>();

    /** 方法被哪些方法调用（反向索引） key=被调用者签名, value=调用者列表 */
    private final Map<String, Set<String>> callersMap = new ConcurrentHashMap<>();

    /**
     * 添加方法定义
     */
    public void addMethod(MethodInfo method) {
        String signature = method.getSignature();
        definedMethods.put(signature, method);
    }

    /**
     * 添加调用边
     */
    public void addCall(CallEdge edge) {
        callEdges.add(edge);

        String callerSig = edge.getCaller() != null ? edge.getCaller().getSignature() : "<unknown>";
        String calleeSig = edge.getCalleeClassName() + "." + edge.getCalleeMethodName() + edge.getCalleeDescriptor();

        // 反向索引
        callersMap.computeIfAbsent(calleeSig, k -> ConcurrentHashMap.newKeySet()).add(callerSig);
    }

    /**
     * 获取方法的调用者
     */
    public Set<String> getCallers(String methodSignature) {
        return callersMap.getOrDefault(methodSignature, Collections.emptySet());
    }

    /**
     * 检查方法是否被调用
     */
    public boolean isCalled(String methodSignature) {
        return !getCallers(methodSignature).isEmpty();
    }

    /**
     * 检查方法是否被调用（模糊匹配，忽略描述符）
     */
    public boolean isCalledByName(String className, String methodName) {
        String prefix = className + "." + methodName + "(";
        for (String key : callersMap.keySet()) {
            if (key.startsWith(prefix) && !callersMap.get(key).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取所有未被调用的方法
     * 排除：入口方法（main）、构造方法、public 方法（可能是 API）、override 方法
     */
    public List<MethodInfo> getUnusedMethods() {
        List<MethodInfo> unused = new ArrayList<>();

        for (MethodInfo method : definedMethods.values()) {
            // 排除标准入口
            if (isEntryMethod(method)) {
                continue;
            }

            // 排除构造方法（一般都会被调用，除非类完全未被使用）
            if (method.isConstructor()) {
                continue;
            }

            // 排除编译器生成方法（lambda 体 lambda$x$0 / 合成访问器 access$000 / 桥方法）：
            // 它们经 invokedynamic 或编译器内部机制调用，源码里永远找不到调用点
            if (method.isSynthetic()) {
                continue;
            }

            // 排除框架反射调用的注解方法（@Scheduled/@PostConstruct/@Test 等），防误报死代码
            if (method.getAnnotations() != null && method.getAnnotations().stream()
                    .anyMatch(FRAMEWORK_INVOKED_ANNOTATIONS::contains)) {
                continue;
            }

            // 检查是否被调用（精确匹配）
            String sig = method.getSignature();
            if (isCalled(sig)) {
                continue;
            }

            // 再按方法名模糊检查（处理多态情况）
            if (isCalledByName(method.getClassName(), method.getMethodName())) {
                continue;
            }

            // R47 兜底：仅按方法名匹配（忽略归属类）。AST 分析中子类调用父类方法、
            // 内部类调用外部类方法的边会归属到调用方所在类，类名精确匹配会漏掉
            // 真实调用点而误报死代码；只要项目内存在同名方法的调用点即视为已使用
            // （宁可漏报不误报，本检查器定位是启发式提示）
            if (isCalledByMethodName(method.getMethodName())) {
                continue;
            }

            unused.add(method);
        }

        return unused;
    }

    /**
     * 检查方法是否被调用（仅按方法名匹配，忽略归属类与描述符）
     */
    public boolean isCalledByMethodName(String methodName) {
        for (Map.Entry<String, Set<String>> entry : callersMap.entrySet()) {
            String key = entry.getKey();
            int dot = key.lastIndexOf('.');
            int paren = key.indexOf('(');
            if (dot >= 0 && paren > dot
                    && key.substring(dot + 1, paren).equals(methodName)
                    && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否是入口方法（不可能被项目内代码调用的方法）
     */
    private boolean isEntryMethod(MethodInfo method) {
        // main 方法
        if ("main".equals(method.getMethodName()) && method.isStatic() && method.isPublic()) {
            return true;
        }

        // public 方法可能被外部调用，保守起见也先排除
        // （如果要更严格的检测，可以关闭此排除）
        if (method.isPublic()) {
            return true;
        }

        // 抽象方法没有实现体
        if (method.isAbstract()) {
            return true;
        }

        // native 方法
        if (method.isNative()) {
            return true;
        }

        // override 方法可能通过父类/接口被调用
        if (method.isOverride()) {
            return true;
        }

        return false;
    }

    /**
     * 获取方法定义数量
     */
    public int getMethodCount() {
        return definedMethods.size();
    }

    /**
     * 获取调用边数量
     */
    public int getCallCount() {
        return callEdges.size();
    }

    /**
     * 打印统计信息
     */
    public void printStats() {
        log.info("调用图统计: 方法={}, 调用边={}, 未被调用={}",
                getMethodCount(), getCallCount(), getUnusedMethods().size());
    }
}
