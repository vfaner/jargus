package com.qqmu.jargus.checker.local;

import com.qqmu.jargus.checker.CheckContext;
import com.qqmu.jargus.checker.CheckIssue;
import com.qqmu.jargus.checker.CheckerType;
import com.qqmu.jargus.checker.IssueLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 并发问题检测器
 *
 * 规则：
 * - CONC_SHARED_MUTABLE_STATE：Spring 单例 Bean 中非 final/非 volatile 的可变实例字段
 * - CONC_STATIC_SDF：静态 SimpleDateFormat（线程不安全，SimpleDateFormat 经典并发陷阱）
 * - CONC_DCL_WITHOUT_VOLATILE：双重检查锁模式中字段缺少 volatile（指令重排可致半初始化对象泄漏）
 */
@Component
public class ConcurrencyChecker extends AbstractLocalChecker {

    /** Spring 单例 stereotype 注解 */
    private static final Set<String> SINGLETON_ANNOTATIONS = Set.of(
            "Controller", "RestController", "Service", "Component", "Repository");

    /** 注入类注解（引用注入后不再变更，不算共享可变状态） */
    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
            "Autowired", "Resource", "Value", "Qualifier", "PersistenceContext",
            "Inject", "ConfigurationProperties");

    /** 不可变类型（引用即使被重新赋值也不破坏对象内部状态，误报高，豁免） */
    private static final Set<String> IMMUTABLE_TYPES = Set.of(
            "String", "Integer", "Long", "Boolean", "Double", "Float", "Short", "Byte",
            "Character", "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime",
            "LocalTime", "Instant", "Duration", "Period");

    /**
     * 并发安全类型：本身就是为跨线程共享设计的（并发容器 / 阻塞队列 / 原子累加器 /
     * 锁与同步器 / 执行器框架），单例 Bean 里非 final 声明也不构成竞态，误报豁免。
     * 原子类（AtomicInteger/AtomicReference 等）另按 "Atomic" 前缀匹配。
     */
    private static final Set<String> CONCURRENT_SAFE_TYPES = Set.of(
            "ConcurrentHashMap", "ConcurrentMap", "ConcurrentSkipListMap", "ConcurrentSkipListSet",
            "CopyOnWriteArrayList", "CopyOnWriteArraySet",
            "ConcurrentLinkedQueue", "ConcurrentLinkedDeque",
            "BlockingQueue", "BlockingDeque", "LinkedBlockingQueue", "LinkedBlockingDeque",
            "ArrayBlockingQueue", "PriorityBlockingQueue", "SynchronousQueue",
            "DelayQueue", "LinkedTransferQueue",
            "LongAdder", "LongAccumulator", "DoubleAdder", "DoubleAccumulator",
            "ReentrantLock", "ReentrantReadWriteLock", "ReadWriteLock", "StampedLock",
            "Semaphore", "CountDownLatch", "CyclicBarrier", "Phaser", "Exchanger",
            "ExecutorService", "ScheduledExecutorService", "ThreadPoolExecutor",
            "ScheduledThreadPoolExecutor", "ForkJoinPool");

    @Override
    public CheckerType getCheckerType() {
        return CheckerType.CONCURRENCY;
    }

    @Override
    public int getPriority() {
        return 40;
    }

    @Override
    protected void doCheck(CheckContext context, CompilationUnit cu, List<CheckIssue> issues) {
        checkSharedMutableState(context, cu, issues);
        checkStaticSimpleDateFormat(context, cu, issues);
        checkDoubleCheckedLocking(context, cu, issues);
    }

    // ------------------------------------------------------- 规则 1：单例 Bean 共享可变状态

    private void checkSharedMutableState(CheckContext context, CompilationUnit cu,
                                         List<CheckIssue> issues) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (clazz.isInterface()) {
                return;
            }
            boolean singleton = clazz.getAnnotations().stream()
                    .anyMatch(a -> SINGLETON_ANNOTATIONS.contains(simpleName(a.getNameAsString())));
            if (!singleton) {
                return;
            }
            for (FieldDeclaration field : clazz.getFields()) {
                if (field.isStatic() || field.isFinal() || field.hasModifier(Modifier.Keyword.VOLATILE)) {
                    continue;
                }
                boolean injected = field.getAnnotations().stream()
                        .anyMatch(a -> INJECTION_ANNOTATIONS.contains(simpleName(a.getNameAsString())));
                if (injected) {
                    continue;
                }
                for (VariableDeclarator var : field.getVariables()) {
                    String typeName = var.getTypeAsString();
                    String varName = var.getNameAsString();
                    if (varName.equalsIgnoreCase("log") || varName.equalsIgnoreCase("logger")) {
                        continue;
                    }
                    // 剥掉泛型实参取原始类型（ConcurrentHashMap<String, X> → ConcurrentHashMap）
                    String rawType = typeName;
                    int lt = rawType.indexOf('<');
                    if (lt >= 0) {
                        rawType = rawType.substring(0, lt).trim();
                    }
                    if (rawType.startsWith("ThreadLocal") || rawType.startsWith("Atomic")
                            || IMMUTABLE_TYPES.contains(rawType)
                            || CONCURRENT_SAFE_TYPES.contains(rawType)) {
                        continue;
                    }
                    int line = field.getBegin().map(p -> p.line).orElse(1);
                    CheckIssue issue = createIssue(
                            IssueLevel.MAJOR,
                            "CONC_SHARED_MUTABLE_STATE",
                            "单例 Bean 存在共享可变状态",
                            "Spring 单例 '" + clazz.getNameAsString() + "' 的实例字段 '" + varName
                                    + "'（类型 " + typeName + "）非 final 且无同步保护，并发请求下存在竞态条件",
                            context.getCurrentFilePath(), line, line);
                    issue.setSuggestion("单例 Bean 应保持无状态：请求级数据用方法参数/局部变量传递，必须共享的状态使用 ThreadLocal、并发容器或加锁保护");
                    issues.add(issue);
                }
            }
        });
    }

    // ------------------------------------------------------- 规则 2：静态 SimpleDateFormat

    private void checkStaticSimpleDateFormat(CheckContext context, CompilationUnit cu,
                                             List<CheckIssue> issues) {
        cu.findAll(FieldDeclaration.class).forEach(field -> {
            if (!field.isStatic()) {
                return;
            }
            for (VariableDeclarator var : field.getVariables()) {
                if (!var.getTypeAsString().equals("SimpleDateFormat")) {
                    continue;
                }
                int line = field.getBegin().map(p -> p.line).orElse(1);
                CheckIssue issue = createIssue(
                        IssueLevel.MAJOR,
                        "CONC_STATIC_SDF",
                        "静态 SimpleDateFormat 线程不安全",
                        "静态字段 '" + var.getNameAsString() + "' 使用 SimpleDateFormat，其内部 Calendar 状态在并发格式化时会产生错误结果甚至异常",
                        context.getCurrentFilePath(), line, line);
                issue.setSuggestion("改用线程安全的 java.time.format.DateTimeFormatter，或 ThreadLocal<SimpleDateFormat>，或每次使用时新建实例");
                issues.add(issue);
            }
        });
    }

    // ------------------------------------------------------- 规则 3：双检锁缺 volatile

    private void checkDoubleCheckedLocking(CheckContext context, CompilationUnit cu,
                                           List<CheckIssue> issues) {
        cu.findAll(SynchronizedStmt.class).forEach(sync -> {
            // synchronized 块内的字段赋值
            sync.findAll(AssignExpr.class).forEach(assign -> {
                if (!(assign.getTarget() instanceof NameExpr target)) {
                    return;
                }
                String fieldName = target.getNameAsString();
                // 所在方法内存在对该字段的 == null 判断（外层或内层检查）
                boolean nullChecked = sync.findAncestor(MethodDeclaration.class)
                        .map(md -> md.findAll(IfStmt.class).stream()
                                .anyMatch(ifStmt -> isNullCheckOn(ifStmt, fieldName)))
                        .orElse(false);
                if (!nullChecked) {
                    return;
                }
                // 字段声明在本类且缺少 volatile
                FieldDeclaration field = sync.findAncestor(CompilationUnit.class)
                        .flatMap(cu2 -> cu2.findAll(FieldDeclaration.class).stream()
                                .filter(f -> f.getVariables().stream()
                                        .anyMatch(v -> v.getNameAsString().equals(fieldName)))
                                .findFirst())
                        .orElse(null);
                if (field == null || field.hasModifier(Modifier.Keyword.VOLATILE)) {
                    return;
                }
                int line = sync.getBegin().map(p -> p.line).orElse(1);
                CheckIssue issue = createIssue(
                        IssueLevel.MAJOR,
                        "CONC_DCL_WITHOUT_VOLATILE",
                        "双重检查锁缺少 volatile",
                        "字段 '" + fieldName + "' 参与 null 检查 + synchronized 的双重检查锁模式，但未声明 volatile；对象构造的指令重排可能让其他线程拿到半初始化实例",
                        context.getCurrentFilePath(), line, line);
                issue.setSuggestion("为该字段添加 volatile 修饰符，或改用静态内部类持有者模式（Holder idiom）实现懒加载单例");
                issues.add(issue);
            });
        });
    }

    /** 取注解简单名（兼容全限定写法 @org.springframework...RestController） */
    private String simpleName(String annotationName) {
        int dot = annotationName.lastIndexOf('.');
        return dot >= 0 ? annotationName.substring(dot + 1) : annotationName;
    }

    private boolean isNullCheckOn(IfStmt ifStmt, String fieldName) {
        if (!(ifStmt.getCondition() instanceof BinaryExpr binary)) {
            return false;
        }
        BinaryExpr.Operator op = binary.getOperator();
        if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) {
            return false;
        }
        boolean leftIsNull = binary.getLeft() instanceof NullLiteralExpr;
        boolean rightIsNull = binary.getRight() instanceof NullLiteralExpr;
        if (!leftIsNull && !rightIsNull) {
            return false;
        }
        var other = leftIsNull ? binary.getRight() : binary.getLeft();
        return other instanceof NameExpr ne && ne.getNameAsString().equals(fieldName);
    }
}
