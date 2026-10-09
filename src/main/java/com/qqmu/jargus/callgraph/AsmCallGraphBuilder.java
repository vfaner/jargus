package com.qqmu.jargus.callgraph;

import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * ASM 字节码调用图构建器
 *
 * 通过分析 .class 文件构建调用图，比 AST 分析更准确：
 * - 能正确识别所有调用（包括 lambda、反射外的动态调用）
 * - 能获取精确的方法描述符
 * - 可以分析第三方库的类（如果有 class 文件）
 */
@Slf4j
public class AsmCallGraphBuilder {

    private final CallGraph callGraph;

    public AsmCallGraphBuilder(CallGraph callGraph) {
        this.callGraph = callGraph;
    }

    /**
     * 分析目录下所有 .class 文件
     */
    public void analyzeDirectory(Path classRoot) throws IOException {
        if (!Files.exists(classRoot) || !Files.isDirectory(classRoot)) {
            log.warn("类目录不存在: {}", classRoot);
            return;
        }

        try (Stream<Path> stream = Files.walk(classRoot)) {
            List<Path> classFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .toList();

            log.info("找到 {} 个 class 文件", classFiles.size());

            for (Path classFile : classFiles) {
                try {
                    analyzeClassFile(classFile, classRoot);
                } catch (Exception e) {
                    log.debug("分析 class 文件失败: {} - {}", classFile, e.getMessage());
                }
            }
        }
    }

    /**
     * 分析单个 .class 文件
     */
    public void analyzeClassFile(Path classFile, Path classRoot) throws IOException {
        try (InputStream is = new FileInputStream(classFile.toFile())) {
            ClassReader cr = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, ClassReader.SKIP_FRAMES);

            String className = classNode.name.replace('/', '.');
            // 源文件相对路径：内部类的 classNode.name 含 $，需用包路径 + SourceFile 属性
            String sourcePath = resolveSourcePath(classNode);

            // 收集方法定义
            for (MethodNode method : classNode.methods) {
                boolean isPublic = (method.access & Opcodes.ACC_PUBLIC) != 0;
                boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                boolean isAbstract = (method.access & Opcodes.ACC_ABSTRACT) != 0;
                boolean isNative = (method.access & Opcodes.ACC_NATIVE) != 0;
                boolean isConstructor = "<init>".equals(method.name) || "<clinit>".equals(method.name);
                // 编译器生成方法（lambda 体 lambda$x$0 / 内部类访问器 access$000 / 桥方法）：
                // 仍要入图分析其内部调用（lambda 体里调用的真实方法靠它记录调用边），
                // 但自身永远不作为死代码报告——它们经 invokedynamic/编译器机制调用，无源码调用点
                boolean isSynthetic = (method.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                        || method.name.contains("$")
                        || method.name.startsWith("lambda$");

                // 从字节码指令流中的 LineNumberNode 取方法真实起止行（抽象/本地方法无行号，保持 0）
                int lineStart = 0;
                int lineEnd = 0;
                if (method.instructions != null) {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof LineNumberNode lnn && lnn.line > 0) {
                            lineStart = lineStart == 0 ? lnn.line : Math.min(lineStart, lnn.line);
                            lineEnd = Math.max(lineEnd, lnn.line);
                        }
                    }
                }

                MethodInfo methodInfo = MethodInfo.builder()
                        .className(className)
                        .methodName(method.name)
                        .descriptor(method.desc)
                        .filePath(sourcePath)
                        .lineStart(lineStart)
                        .lineEnd(lineEnd)
                        .isPublic(isPublic)
                        .isStatic(isStatic)
                        .isConstructor(isConstructor)
                        .isAbstract(isAbstract)
                        .isNative(isNative)
                        .isSynthetic(isSynthetic)
                        .annotations(annotationNames(method))
                        .build();

                callGraph.addMethod(methodInfo);

                // 分析方法内的调用
                analyzeMethodCalls(methodInfo, method);
            }
        }
    }

    /**
     * 由字节码元数据推导源文件相对路径（包路径 + SourceFile 属性），
     * SourceFile 缺失时回退到 类名.java（内部类会退化为 外部$内部.java）
     */
    private String resolveSourcePath(ClassNode classNode) {
        String packagePath = "";
        int slash = classNode.name.lastIndexOf('/');
        if (slash >= 0) {
            packagePath = classNode.name.substring(0, slash + 1);
        }
        if (classNode.sourceFile != null && !classNode.sourceFile.isEmpty()) {
            return packagePath + classNode.sourceFile;
        }
        int dollar = classNode.name.lastIndexOf('$');
        String simple = dollar >= 0 ? classNode.name.substring(dollar + 1)
                : classNode.name.substring(slash + 1);
        return packagePath + simple + ".java";
    }

    /**
     * 收集方法注解的简单名：字节码注解描述符形如
     * {@code Lorg/springframework/scheduling/annotation/Scheduled;}，
     * 取最后一个 '/' 之后、';' 之前的部分
     */
    private Set<String> annotationNames(MethodNode method) {
        Set<String> names = new HashSet<>();
        collectAnnotationNames(method.visibleAnnotations, names);
        collectAnnotationNames(method.invisibleAnnotations, names);
        return names;
    }

    private void collectAnnotationNames(List<AnnotationNode> annotations, Set<String> names) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            String desc = annotation.desc;
            if (desc == null || desc.length() < 3) continue;
            int slash = desc.lastIndexOf('/');
            int semi = desc.lastIndexOf(';');
            if (semi > slash + 1) {
                names.add(desc.substring(slash + 1, semi));
            }
        }
    }

    /**
     * 分析方法内的调用指令
     */
    private void analyzeMethodCalls(MethodInfo caller, MethodNode methodNode) {
        if (methodNode.instructions == null) return;

        methodNode.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode methodInsn) {
                CallEdge.CallType callType = switch (methodInsn.getOpcode()) {
                    case Opcodes.INVOKESTATIC -> CallEdge.CallType.STATIC;
                    case Opcodes.INVOKESPECIAL -> CallEdge.CallType.SPECIAL;
                    case Opcodes.INVOKEVIRTUAL -> CallEdge.CallType.VIRTUAL;
                    case Opcodes.INVOKEINTERFACE -> CallEdge.CallType.INTERFACE;
                    default -> CallEdge.CallType.VIRTUAL;
                };

                String calleeClassName = methodInsn.owner.replace('/', '.');

                CallEdge edge = CallEdge.builder()
                        .caller(caller)
                        .calleeClassName(calleeClassName)
                        .calleeMethodName(methodInsn.name)
                        .calleeDescriptor(methodInsn.desc)
                        .callType(callType)
                        .lineNumber(0) // ASM 也可以获取行号，需要访问 LineNumberNode
                        .build();

                callGraph.addCall(edge);
            } else if (insn instanceof InvokeDynamicInsnNode indyInsn) {
                // invokedynamic 的 bootstrap 参数里的方法句柄才是 lambda / 方法引用的真实目标：
                // Foo::bar 这类方法引用在字节码中没有任何普通调用指令，只以 Handle 形式
                // 挂在这里；漏收会让被引用的方法被误报死代码
                for (Object bsmArg : indyInsn.bsmArgs) {
                    if (bsmArg instanceof Handle handle) {
                        CallEdge edge = CallEdge.builder()
                                .caller(caller)
                                .calleeClassName(handle.getOwner().replace('/', '.'))
                                .calleeMethodName(handle.getName())
                                .calleeDescriptor(handle.getDesc())
                                .callType(CallEdge.CallType.VIRTUAL)
                                .lineNumber(0)
                                .build();
                        callGraph.addCall(edge);
                    }
                }
            }
        });
    }
}
