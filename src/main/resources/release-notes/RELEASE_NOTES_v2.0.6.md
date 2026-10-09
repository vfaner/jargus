# 百目 JArgus v2.0.6

静态检查器误报治理专项版本：注解修饰的代码、编译器生成方法与框架反射调用点不再被误判，死代码检测理解 lambda 与方法引用，多个检查器的豁免规则对齐业界惯例。

## 本次更新

### 死代码检测（未使用方法）

- **框架反射调用的方法不再误报死代码**：@Scheduled、@PostConstruct/@PreDestroy、@EventListener、@KafkaListener/@RabbitListener/@JmsListener 等消息监听、@Bean、@ExceptionHandler、AspectJ 通知（@Around/@Before 等）、JUnit 测试方法（@Test/@BeforeEach 等）、Jackson 序列化回调（@JsonCreator/@JsonValue 等）共 31 种注解标记的方法自动豁免——它们由调度器、容器、事件总线、测试运行器在运行时调用，源码里本就没有调用点。
- **编译器生成方法不再误报**：lambda 体（lambda$x$0）、内部类合成访问器（access$000）、桥方法经 invokedynamic 或编译器内部机制调用，字节码与 AST 两条分析路径均识别并排除；lambda 体内部的真实调用边仍正常入图。
- **方法引用算作使用**：`Foo::bar` 在字节码中经 invokedynamic 引用目标方法、没有普通调用指令，之前会被误报死代码；现在解析 bootstrap 方法句柄补齐调用边。

### 检查器误报治理

- **魔法数字**：注解属性中的数字（@Size(max=500)、@Retryable(maxAttempts=3)）与 @interface 成员默认值属框架配置语义，不再报告。
- **System.out**：main 方法（CLI 入口与终端交互的正常手段）与测试代码（src/test 目录、*Test/*Tests/*TestCase/*IT 命名）中的输出不再报告。
- **废弃方法调用**：只匹配无 scope 或 this/super 的同类调用，`stream.close()` 这类跨对象同名调用不再因本地恰有同名废弃方法被误伤；废弃方法内部或 @SuppressWarnings("deprecation") 之下的调用视为既定迁移路径不报告；同一调用点命中多个废弃重载只报一次。
- **并发问题**：并发容器（ConcurrentHashMap/CopyOnWriteArrayList 等）、阻塞队列、Atomic* 原子类、锁与同步器（ReentrantLock/CountDownLatch/Semaphore 等）、执行器框架本身就是为跨线程共享设计的，单例 Bean 中以非 final 字段声明不再触发共享可变状态告警。
- **命名规范**：serialVersionUID（JLS 规定固定名）与 Logger 声明惯例（private static final Logger log = ...）不再要求全大写常量命名；接口字段隐式 public static final，改按常量规则检查，接口里的 MAX_SIZE 不再被误报小驼峰违规。

## 升级须知

- 数据结构、加密密钥、Cookie、CI Token 与 v2.0.0 起的版本一致，直接覆盖 jar 即可。
- 本版本只收紧误报豁免、不新增规则类型；历史任务重扫后问题清单会相应减少，属预期行为。
