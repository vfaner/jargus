package com.qqmu.jargus.callgraph;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Collections;
import java.util.Set;

/**
 * 方法信息
 */
@Data
@Builder
@EqualsAndHashCode(of = {"className", "methodName", "descriptor"})
public class MethodInfo {

    /** 类名（全限定名） */
    private String className;

    /** 方法名 */
    private String methodName;

    /** 方法描述符（参数+返回值），如 (Ljava/lang/String;)I */
    private String descriptor;

    /** 源文件路径 */
    private String filePath;

    /** 起始行号 */
    private int lineStart;

    /** 结束行号 */
    private int lineEnd;

    /** 是否是 public 方法 */
    private boolean isPublic;

    /** 是否是 static 方法 */
    private boolean isStatic;

    /** 是否是构造方法 */
    private boolean isConstructor;

    /** 是否是抽象方法 */
    private boolean isAbstract;

    /** 是否是 native 方法 */
    private boolean isNative;

    /** 是否是 override 方法 */
    private boolean isOverride;

    /** 是否是编译器生成方法（lambda 体 / 合成访问器 / 桥方法），永远不作为死代码报告 */
    @Builder.Default
    private boolean isSynthetic = false;

    /** 方法上的注解简单名集合（如 Scheduled、PostConstruct），框架反射调用的豁免依据 */
    @Builder.Default
    private Set<String> annotations = Collections.emptySet();

    /**
     * 获取方法的唯一签名
     * 格式：className.methodName(descriptor)
     */
    public String getSignature() {
        return className + "." + methodName + descriptor;
    }
}
