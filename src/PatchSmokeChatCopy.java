import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.FrameNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import jdk.internal.org.objectweb.asm.tree.JumpInsnNode;
import jdk.internal.org.objectweb.asm.tree.LabelNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TypeInsnNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;

/**
 * 为指定 Smoke 客户端生成聊天复制独立补丁的工具。
 */
public final class PatchSmokeChatCopy {
    private static final String EXPECTED_SHA256 = "C600FD3C23FB95437B76CA7AC83EA60A26832BECB3796ED9F47DC519EF541CCD"; // 唯一允许处理的原始 Smoke 客户端摘要
    private static final String CHAT_INPUT_ENTRY = "net/minecraft/client/gui/GuiChat.class"; // 聊天鼠标点击入口类
    private static final String CHAT_LOOKUP_ENTRY = "net/minecraft/client/gui/GuiNewChat.class"; // 聊天组件命中入口类
    private static final String AUTO_GG_ENTRY = "icu/hanabi/smoke/module/collection/module/AutoGG.class"; // Smoke 内置 AutoGG 模块类
    private static final String CHAT_COPY_HOOK_CLASS_NAME = "ChatCopyHook"; // 写入客户端的聊天复制运行时钩子类名
    private static final String AUTO_GG_HOOK_CLASS_NAME = "AutoGGHook"; // 写入客户端的 AutoGG 运行时钩子类名
    private static final String MOUSE_CLICK_METHOD_NAME = "mouseClicked"; // GuiChat 鼠标点击方法名称
    private static final String MOUSE_CLICK_METHOD_DESCRIPTOR = "(III)V"; // GuiChat 鼠标点击方法描述符
    private static final String CHAT_LOOKUP_METHOD_NAME = "getChatComponent"; // GuiNewChat 消息命中方法名称
    private static final String CHAT_LOOKUP_METHOD_DESCRIPTOR = "(II)Lnet/minecraft/util/IChatComponent;"; // GuiNewChat 消息命中方法描述符
    private static final String AUTO_GG_METHOD_NAME = "onGG"; // AutoGG 聊天事件方法名称
    private static final String AUTO_GG_METHOD_DESCRIPTOR = "(Licu/hanabi/smoke/events/Misc/EventChat;)V"; // AutoGG 聊天事件方法描述符
    private static final String CHAT_COPY_HOOK_OWNER = CHAT_COPY_HOOK_CLASS_NAME; // 默认包聊天复制钩子的 JVM 所有者名称
    private static final String AUTO_GG_HOOK_OWNER = AUTO_GG_HOOK_CLASS_NAME; // 默认包 AutoGG 钩子的 JVM 所有者名称
    private static final int LOCAL_FILE_HEADER_SIGNATURE = 0x04034B50; // ZIP 本地文件头标识
    private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014B50; // ZIP 中央目录项标识
    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054B50; // ZIP 中央目录结束标识
    private static final int UTF8_FLAG = 0x0800; // ZIP 条目名称使用 UTF-8 的标志位
    private static final int DATA_DESCRIPTOR_FLAG = 0x0008; // ZIP 条目使用数据描述符的标志位

    /**
     * 禁止实例化纯静态的补丁工具。
     */
    private PatchSmokeChatCopy() {
    }

    /**
     * 根据原始 Smoke Jar 和已编译钩子类创建独立补丁 Jar。
     *
     * @param arguments 参数依次为原始 Jar、钩子类目录和输出 Jar
     * @throws Exception 文件、校验或字节码处理失败时抛出异常
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Usage: PatchSmokeChatCopy <Smoke.jar> <hook-class-directory> <output-jar>");
        }

        Path inputJar = Paths.get(arguments[0]).toAbsolutePath().normalize();
        Path hookClassDirectory = Paths.get(arguments[1]).toAbsolutePath().normalize();
        Path outputJar = Paths.get(arguments[2]).toAbsolutePath().normalize();

        if (Files.exists(outputJar)) {
            throw new IOException("Output file already exists: " + outputJar);
        }

        RawZipArchive archive = verifyInputJar(inputJar);
        Map<String, byte[]> hookClasses = readHookClasses(hookClassDirectory);
        verifyHookEntriesAbsent(archive, hookClasses);
        writePatchedOutput(archive, hookClasses, outputJar);
    }

    /**
     * 校验输入 Jar 摘要、目标类和未注入状态。
     *
     * @param inputJar 待处理的原始 Smoke Jar
     * @return 可供后续改写的原始 ZIP 归档
     * @throws Exception 输入文件不符合目标客户端时抛出异常
     */
    private static RawZipArchive verifyInputJar(Path inputJar) throws Exception {
        if (!Files.isRegularFile(inputJar)) {
            throw new IOException("Client jar does not exist: " + inputJar);
        }

        String actualHash = calculateSha256(inputJar);

        if (!EXPECTED_SHA256.equalsIgnoreCase(actualHash)) {
            throw new IOException("Unexpected Smoke.jar SHA-256: " + actualHash);
        }

        RawZipArchive archive = RawZipArchive.read(inputJar);
        RawZipEntry chatInputEntry = archive.getEntry(CHAT_INPUT_ENTRY);
        RawZipEntry chatLookupEntry = archive.getEntry(CHAT_LOOKUP_ENTRY);
        RawZipEntry autoGGEntry = archive.getEntry(AUTO_GG_ENTRY);

        if (chatInputEntry == null || chatLookupEntry == null || autoGGEntry == null) {
            throw new IOException("Required Smoke chat classes are missing.");
        }

        verifyTargetClass(archive.readEntry(chatInputEntry), CHAT_INPUT_ENTRY, MOUSE_CLICK_METHOD_NAME, MOUSE_CLICK_METHOD_DESCRIPTOR);
        verifyTargetClass(archive.readEntry(chatLookupEntry), CHAT_LOOKUP_ENTRY, CHAT_LOOKUP_METHOD_NAME, CHAT_LOOKUP_METHOD_DESCRIPTOR);
        verifyTargetClass(archive.readEntry(autoGGEntry), AUTO_GG_ENTRY, AUTO_GG_METHOD_NAME, AUTO_GG_METHOD_DESCRIPTOR);
        return archive;
    }

    /**
     * 校验目标类版本、方法签名和钩子调用状态。
     *
     * @param classBytes 目标类的未压缩字节码
     * @param entryName 目标类条目名称
     * @param methodName 需要存在的方法名称
     * @param methodDescriptor 需要存在的方法描述符
     * @throws IOException 类版本、方法或注入状态不符合预期时抛出异常
     */
    private static void verifyTargetClass(byte[] classBytes, String entryName, String methodName, String methodDescriptor) throws IOException {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.EXPAND_FRAMES);

        if (classNode.version != Opcodes.V1_8) {
            throw new IOException("Unexpected class version in " + entryName + ": " + classNode.version);
        }

        MethodNode targetMethod = findMethod(classNode, methodName, methodDescriptor);

        if (targetMethod == null) {
            throw new IOException("Missing target method " + methodName + methodDescriptor + " in " + entryName);
        }

        if (containsRuntimeHookInvocation(targetMethod)) {
            throw new IOException("Runtime hook is already present in " + entryName);
        }
    }

    /**
     * 读取编译出的钩子主类及其内部类。
     *
     * @param hookClassDirectory 钩子类文件所在目录
     * @return 将写入客户端 Jar 的类文件映射
     * @throws IOException 钩子类缺失或读取失败时抛出异常
     */
    private static Map<String, byte[]> readHookClasses(Path hookClassDirectory) throws IOException {
        if (!Files.isDirectory(hookClassDirectory)) {
            throw new IOException("Hook class directory does not exist: " + hookClassDirectory);
        }

        List<Path> classFiles = new ArrayList<Path>();

        try (java.nio.file.DirectoryStream<Path> directoryStream = Files.newDirectoryStream(hookClassDirectory, "*.class")) {
            for (Path classFile : directoryStream) {
                if (isRuntimeHookClass(classFile.getFileName().toString())) {
                    classFiles.add(classFile);
                }
            }
        }

        Collections.sort(classFiles, new Comparator<Path>() {
            /**
             * 按类文件名稳定排序，保证补丁输出顺序可复现。
             *
             * @param first 第一条路径
             * @param second 第二条路径
             * @return 文件名的字典序比较结果
             */
            @Override
            public int compare(Path first, Path second) {
                return first.getFileName().toString().compareTo(second.getFileName().toString());
            }
        });

        Map<String, byte[]> hookClasses = new LinkedHashMap<String, byte[]>();

        for (Path classFile : classFiles) {
            hookClasses.put(classFile.getFileName().toString(), Files.readAllBytes(classFile));
        }

        if (!hookClasses.containsKey(CHAT_COPY_HOOK_CLASS_NAME + ".class")
                || !hookClasses.containsKey(AUTO_GG_HOOK_CLASS_NAME + ".class")) {
            throw new IOException("Missing compiled runtime hook class in: " + hookClassDirectory);
        }

        return hookClasses;
    }

    /**
     * 判断编译产物是否属于需要写入客户端的运行时钩子类。
     *
     * @param fileName 编译后的类文件名称
     * @return 是否为聊天复制或 AutoGG 钩子及其内部类
     */
    private static boolean isRuntimeHookClass(String fileName) {
        return fileName.startsWith(CHAT_COPY_HOOK_CLASS_NAME)
                || fileName.startsWith(AUTO_GG_HOOK_CLASS_NAME);
    }

    /**
     * 确认原始 Jar 没有与待追加钩子冲突的条目。
     *
     * @param archive 原始 ZIP 归档
     * @param hookClasses 待写入的钩子类映射
     * @throws IOException 存在同名条目时抛出异常
     */
    private static void verifyHookEntriesAbsent(RawZipArchive archive, Map<String, byte[]> hookClasses) throws IOException {
        for (String entryName : hookClasses.keySet()) {
            if (archive.getEntry(entryName) != null) {
                throw new IOException("Client jar already contains " + entryName);
            }
        }
    }

    /**
     * 替换聊天类并追加运行时钩子，然后原子写入独立输出 Jar。
     *
     * @param archive 已校验的原始 ZIP 归档
     * @param hookClasses 待追加的钩子类映射
     * @param outputJar 独立补丁输出位置
     * @throws Exception 字节码转换或写入失败时抛出异常
     */
    private static void writePatchedOutput(RawZipArchive archive, Map<String, byte[]> hookClasses, Path outputJar) throws Exception {
        RawZipEntry chatInputEntry = archive.getEntry(CHAT_INPUT_ENTRY);
        RawZipEntry chatLookupEntry = archive.getEntry(CHAT_LOOKUP_ENTRY);
        RawZipEntry autoGGEntry = archive.getEntry(AUTO_GG_ENTRY);
        chatInputEntry.replace( transformChatInput(archive.readEntry(chatInputEntry)) );
        chatLookupEntry.replace( transformChatComponentLookup(archive.readEntry(chatLookupEntry)) );
        autoGGEntry.replace(transformAutoGG(archive.readEntry(autoGGEntry)));

        List<RawZipEntry> appendedEntries = new ArrayList<RawZipEntry>();

        for (Map.Entry<String, byte[]> hookClass : hookClasses.entrySet()) {
            appendedEntries.add(RawZipEntry.createNew(hookClass.getKey(), hookClass.getValue()));
        }

        Path temporaryJar = Files.createTempFile(outputJar.getParent(), "Smoke.chatcopy.", ".tmp");

        try {
            archive.write(temporaryJar, appendedEntries);

            try {
                Files.move(temporaryJar, outputJar, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryJar, outputJar);
            }
        } catch (Exception exception) {
            Files.deleteIfExists(temporaryJar);
            throw exception;
        }
    }

    /**
     * 将复制判断插入 GuiChat 鼠标点击方法的最前方。
     *
     * @param originalClass 原始 GuiChat 类字节码
     * @return 注入后的 GuiChat 类字节码
     * @throws IOException 目标方法不存在或已被注入时抛出异常
     */
    private static byte[] transformChatInput(byte[] originalClass) throws IOException {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.EXPAND_FRAMES);
        MethodNode mouseClickMethod = findMethod(classNode, MOUSE_CLICK_METHOD_NAME, MOUSE_CLICK_METHOD_DESCRIPTOR);

        if (mouseClickMethod == null) {
            throw new IOException("Missing chat click method in " + CHAT_INPUT_ENTRY);
        }

        if (containsHookInvocation(mouseClickMethod, CHAT_COPY_HOOK_OWNER, "tryCopy")) {
            throw new IOException("Chat copy hook is already present in " + CHAT_INPUT_ENTRY);
        }

        injectCopyHandling(classNode, mouseClickMethod);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * 为 GuiNewChat 的命中方法注入精确渲染行记录逻辑。
     *
     * @param originalClass 原始 GuiNewChat 类字节码
     * @return 注入后的 GuiNewChat 类字节码
     * @throws IOException 目标局部变量结构不符合预期时抛出异常
     */
    private static byte[] transformChatComponentLookup(byte[] originalClass) throws IOException {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.EXPAND_FRAMES);
        MethodNode lookupMethod = findMethod(classNode, CHAT_LOOKUP_METHOD_NAME, CHAT_LOOKUP_METHOD_DESCRIPTOR);

        if (lookupMethod == null) {
            throw new IOException("Missing chat lookup method in " + CHAT_LOOKUP_ENTRY);
        }

        if (containsHookInvocation(lookupMethod, CHAT_COPY_HOOK_OWNER, "rememberCandidateLine")) {
            throw new IOException("Hovered chat line hook is already present in " + CHAT_LOOKUP_ENTRY);
        }

        injectCandidateLineTracking(lookupMethod);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * 在 Smoke AutoGG 事件入口注入遗漏 Hypixel 结算规则处理。
     *
     * @param originalClass 原始 AutoGG 模块类字节码
     * @return 注入后的 AutoGG 模块类字节码
     * @throws IOException 目标方法不存在或已被注入时抛出异常
     */
    private static byte[] transformAutoGG(byte[] originalClass) throws IOException {
        ClassReader reader = new ClassReader(originalClass);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.EXPAND_FRAMES);
        MethodNode autoGGMethod = findMethod(classNode, AUTO_GG_METHOD_NAME, AUTO_GG_METHOD_DESCRIPTOR);

        if (autoGGMethod == null) {
            throw new IOException("Missing AutoGG event method in " + AUTO_GG_ENTRY);
        }

        if (containsHookInvocation(autoGGMethod, AUTO_GG_HOOK_OWNER, "trySend")) {
            throw new IOException("AutoGG hook is already present in " + AUTO_GG_ENTRY);
        }

        injectAutoGGHandling(classNode, autoGGMethod);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * 在类节点中查找给定名称和描述符的方法。
     *
     * @param classNode 待搜索的类节点
     * @param methodName 方法名称
     * @param methodDescriptor 方法描述符
     * @return 匹配的方法，未找到时返回 null
     */
    private static MethodNode findMethod(ClassNode classNode, String methodName, String methodDescriptor) {
        for (MethodNode method : classNode.methods) {
            if (methodName.equals(method.name) && methodDescriptor.equals(method.desc)) {
                return method;
            }
        }

        return null;
    }

    /**
     * 检查方法中是否已经存在指定运行时钩子调用。
     *
     * @param method 待检查的方法
     * @param hookOwner 钩子类的 JVM 所有者名称
     * @param hookMethod 钩子方法名称
     * @return 是否已经注入对应调用
     */
    private static boolean containsHookInvocation(MethodNode method, String hookOwner, String hookMethod) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode invocation = (MethodInsnNode) instruction;

                if (hookOwner.equals(invocation.owner) && hookMethod.equals(invocation.name)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 检查方法中是否已经存在任一运行时钩子调用。
     *
     * @param method 待检查的方法
     * @return 是否已经注入聊天复制或 AutoGG 钩子
     */
    private static boolean containsRuntimeHookInvocation(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode invocation = (MethodInsnNode) instruction;

                if (CHAT_COPY_HOOK_OWNER.equals(invocation.owner) || AUTO_GG_HOOK_OWNER.equals(invocation.owner)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 在精确 ChatLine 写入局部变量后立即记录该渲染行。
     *
     * @param lookupMethod GuiNewChat 的聊天组件命中方法
     * @throws IOException 找不到预期 ChatLine 局部变量时抛出异常
     */
    private static void injectCandidateLineTracking(MethodNode lookupMethod) throws IOException {
        for (AbstractInsnNode instruction = lookupMethod.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof VarInsnNode)
                    || instruction.getOpcode() != Opcodes.ASTORE
                    || ((VarInsnNode) instruction).var != 10) {
                continue;
            }

            AbstractInsnNode previousInstruction = instruction.getPrevious();

            if (!(previousInstruction instanceof TypeInsnNode)
                    || previousInstruction.getOpcode() != Opcodes.CHECKCAST
                    || !"net/minecraft/client/gui/ChatLine".equals(((TypeInsnNode) previousInstruction).desc)) {
                continue;
            }

            InsnList trackingInstructions = new InsnList();
            trackingInstructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            trackingInstructions.add(new VarInsnNode(Opcodes.ALOAD, 10));
            trackingInstructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CHAT_COPY_HOOK_OWNER, "rememberCandidateLine", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            lookupMethod.instructions.insert(instruction, trackingInstructions); // 在 ChatLine 赋值后立即记录，避免读取未初始化局部变量
            return;
        }

        throw new IOException("Missing ChatLine candidate local variable in " + CHAT_LOOKUP_ENTRY);
    }

    /**
     * 生成并插入聊天复制的字节码前缀，未命中时跳回原始点击流程。
     *
     * @param classNode GuiChat 类节点
     * @param mouseClickMethod 鼠标点击目标方法
     */
    private static void injectCopyHandling(ClassNode classNode, MethodNode mouseClickMethod) {
        LabelNode inspectChatMessage = new LabelNode();
        LabelNode continueOriginalHandling = new LabelNode();
        InsnList prefix = new InsnList();

        prefix.add(new VarInsnNode(Opcodes.ILOAD, 3));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, inspectChatMessage));
        prefix.add(new VarInsnNode(Opcodes.ILOAD, 3));
        prefix.add(new InsnNode(Opcodes.ICONST_1));
        prefix.add(new JumpInsnNode(Opcodes.IF_ICMPNE, continueOriginalHandling));
        prefix.add(inspectChatMessage);
        prefix.add(createInitialFrame(classNode));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new FieldInsnNode(Opcodes.GETFIELD, classNode.name, "mc", "Lnet/minecraft/client/Minecraft;"));
        prefix.add(new InsnNode(Opcodes.DUP));
        prefix.add(new FieldInsnNode(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "ingameGUI", "Lnet/minecraft/client/gui/GuiIngame;"));
        prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/gui/GuiIngame", "getChatGUI", "()Lnet/minecraft/client/gui/GuiNewChat;", false));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/input/Mouse", "getX", "()I", false));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/lwjgl/input/Mouse", "getY", "()I", false));
        prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/gui/GuiNewChat", "getChatComponent", "(II)Lnet/minecraft/util/IChatComponent;", false));
        prefix.add(new VarInsnNode(Opcodes.ILOAD, 3));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CHAT_COPY_HOOK_OWNER, "tryCopy", "(Ljava/lang/Object;Ljava/lang/Object;I)Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginalHandling));
        prefix.add(new InsnNode(Opcodes.RETURN));
        prefix.add(continueOriginalHandling);
        prefix.add(createInitialFrame(classNode));

        mouseClickMethod.instructions.insert(prefix); // 在原始组件点击、输入框和父类处理前优先尝试复制
    }

    /**
     * 在原 AutoGG 处理前优先处理其遗漏的 Hypixel 结算规则。
     *
     * @param classNode 当前 AutoGG 类节点
     * @param autoGGMethod AutoGG 聊天事件方法
     */
    private static void injectAutoGGHandling(ClassNode classNode, MethodNode autoGGMethod) {
        LabelNode continueOriginalHandling = new LabelNode();
        InsnList prefix = new InsnList();

        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AUTO_GG_HOOK_OWNER, "trySend", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
        prefix.add(new JumpInsnNode(Opcodes.IFEQ, continueOriginalHandling));
        prefix.add(new InsnNode(Opcodes.RETURN));
        prefix.add(continueOriginalHandling);
        prefix.add(createAutoGGInitialFrame(classNode));

        autoGGMethod.instructions.insert(prefix); // Hypixel 结算命中后跳过旧方法中遗漏该数组的逻辑
    }

    /**
     * 为新增跳转目标创建与方法入口一致的完整 StackMap Frame。
     *
     * @param classNode 当前 GuiChat 类节点
     * @return 可写入跳转目标的初始栈帧
     */
    private static FrameNode createInitialFrame(ClassNode classNode) {
        return new FrameNode(Opcodes.F_NEW, 4, new Object[] {classNode.name, Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER}, 0, new Object[0]);
    }

    /**
     * 为新增 AutoGG 跳转目标创建与事件方法入口一致的 StackMap Frame。
     *
     * @param classNode 当前 AutoGG 类节点
     * @return 可写入事件继续分支的初始栈帧
     */
    private static FrameNode createAutoGGInitialFrame(ClassNode classNode) {
        return new FrameNode(Opcodes.F_NEW, 2, new Object[] {classNode.name, "icu/hanabi/smoke/events/Misc/EventChat"}, 0, new Object[0]);
    }

    /**
     * 计算文件的 SHA-256 十六进制摘要。
     *
     * @param file 待计算摘要的文件
     * @return 大写 SHA-256 摘要
     * @throws IOException 读取文件失败时抛出异常
     * @throws NoSuchAlgorithmException 当前 JRE 不支持 SHA-256 时抛出异常
     */
    private static String calculateSha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];

        try (InputStream input = Files.newInputStream(file)) {
            int read;

            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        StringBuilder hexadecimal = new StringBuilder();

        for (byte value : digest.digest()) {
            hexadecimal.append(String.format("%02X", Byte.valueOf(value)));
        }

        return hexadecimal.toString();
    }

    /**
     * 表示可保留原始名称字节和元数据的 ZIP 归档。
     */
    private static final class RawZipArchive {
        private final byte[] source;
        private final List<RawZipEntry> entries;
        private final List<RawZipEntry> localOrder;
        private final byte[] prefix;
        private final byte[] endComment;

        /**
         * 创建已解析的原始 ZIP 归档。
         *
         * @param source 原始 Jar 全部字节
         * @param entries 中央目录顺序的条目列表
         * @param localOrder 本地文件记录顺序的条目列表
         * @param prefix 首个本地文件记录前的原始字节
         * @param endComment 中央目录结束记录后的注释字节
         */
        private RawZipArchive(byte[] source, List<RawZipEntry> entries, List<RawZipEntry> localOrder, byte[] prefix, byte[] endComment) {
            this.source = source;
            this.entries = entries;
            this.localOrder = localOrder;
            this.prefix = prefix;
            this.endComment = endComment;
        }

        /**
         * 从原始字节解析 ZIP 中央目录而不解码所有条目名称。
         *
         * @param jarFile 待读取的 Smoke Jar
         * @return 解析后的原始 ZIP 归档
         * @throws IOException ZIP 结构不受支持或损坏时抛出异常
         */
        private static RawZipArchive read(Path jarFile) throws IOException {
            byte[] source = Files.readAllBytes(jarFile);
            int endOfDirectory = findEndOfCentralDirectory(source);
            int entryCount = readUnsignedShort(source, endOfDirectory + 10);
            int centralDirectorySize = checkedInt(readUnsignedInt(source, endOfDirectory + 12), "central directory size");
            int centralDirectoryOffset = checkedInt(readUnsignedInt(source, endOfDirectory + 16), "central directory offset");
            int commentLength = readUnsignedShort(source, endOfDirectory + 20);

            if (entryCount == 0 || centralDirectoryOffset < 0 || centralDirectorySize < 0
                    || centralDirectoryOffset + centralDirectorySize > endOfDirectory
                    || endOfDirectory + 22 + commentLength != source.length) {
                throw new IOException("Unsupported or malformed ZIP central directory.");
            }

            List<RawZipEntry> entries = new ArrayList<RawZipEntry>();
            int cursor = centralDirectoryOffset;

            for (int index = 0; index < entryCount; index++) {
                if (readUnsignedInt(source, cursor) != unsignedInt(CENTRAL_DIRECTORY_SIGNATURE)) {
                    throw new IOException("Invalid central directory entry at index " + index);
                }

                int nameLength = readUnsignedShort(source, cursor + 28);
                int extraLength = readUnsignedShort(source, cursor + 30);
                int entryCommentLength = readUnsignedShort(source, cursor + 32);
                int recordLength = 46 + nameLength + extraLength + entryCommentLength;

                if (cursor + recordLength > centralDirectoryOffset + centralDirectorySize) {
                    throw new IOException("Truncated central directory entry at index " + index);
                }

                byte[] rawCentralDirectory = Arrays.copyOfRange(source, cursor, cursor + recordLength);
                byte[] rawName = Arrays.copyOfRange(source, cursor + 46, cursor + 46 + nameLength);
                int flags = readUnsignedShort(rawCentralDirectory, 8);
                int method = readUnsignedShort(rawCentralDirectory, 10);
                int localOffset = checkedInt(readUnsignedInt(rawCentralDirectory, 42), "local entry offset");
                entries.add(new RawZipEntry(rawName, rawCentralDirectory, flags, method, localOffset));
                cursor += recordLength;
            }

            if (cursor != centralDirectoryOffset + centralDirectorySize) {
                throw new IOException("Unexpected trailing data in central directory.");
            }

            List<RawZipEntry> localOrder = new ArrayList<RawZipEntry>(entries);
            Collections.sort(localOrder, new Comparator<RawZipEntry>() {
                /**
                 * 按原始本地记录偏移排序以保留数据描述符和额外字节。
                 *
                 * @param first 第一条 ZIP 条目
                 * @param second 第二条 ZIP 条目
                 * @return 本地记录偏移比较结果
                 */
                @Override
                public int compare(RawZipEntry first, RawZipEntry second) {
                    return first.localOffset - second.localOffset;
                }
            });

            int firstLocalOffset = localOrder.get(0).localOffset;

            if (firstLocalOffset < 0 || firstLocalOffset > centralDirectoryOffset) {
                throw new IOException("Invalid first local entry offset.");
            }

            for (int index = 0; index < localOrder.size(); index++) {
                RawZipEntry entry = localOrder.get(index);
                int nextOffset = index + 1 < localOrder.size()
                        ? localOrder.get(index + 1).localOffset
                        : centralDirectoryOffset;

                if (entry.localOffset < firstLocalOffset || entry.localOffset >= nextOffset || nextOffset > centralDirectoryOffset) {
                    throw new IOException("Overlapping or invalid local ZIP entries.");
                }

                if (readUnsignedInt(source, entry.localOffset) != unsignedInt(LOCAL_FILE_HEADER_SIGNATURE)) {
                    throw new IOException("Invalid local file header for " + entry.getDisplayName());
                }

                entry.localBytes = Arrays.copyOfRange(source, entry.localOffset, nextOffset);
            }

            byte[] prefix = Arrays.copyOfRange(source, 0, firstLocalOffset);
            byte[] endComment = Arrays.copyOfRange(source, endOfDirectory + 22, source.length);
            return new RawZipArchive(source, entries, localOrder, prefix, endComment);
        }

        /**
         * 取得指定 UTF-8 条目名称对应的归档项。
         *
         * @param entryName 目标条目名称
         * @return 匹配的条目，未找到时返回 null
         */
        private RawZipEntry getEntry(String entryName) {
            byte[] expectedName = entryName.getBytes(StandardCharsets.UTF_8);

            for (RawZipEntry entry : entries) {
                if (Arrays.equals(expectedName, entry.rawName)) {
                    return entry;
                }
            }

            return null;
        }

        /**
         * 解压读取指定原始条目内容。
         *
         * @param entry 待读取的条目
         * @return 未压缩条目内容
         * @throws IOException 压缩方式不支持或条目损坏时抛出异常
         */
        private byte[] readEntry(RawZipEntry entry) throws IOException {
            if ((entry.flags & 0x0001) != 0) {
                throw new IOException("Encrypted ZIP entry is not supported: " + entry.getDisplayName());
            }

            int nameLength = readUnsignedShort(entry.localBytes, 26);
            int extraLength = readUnsignedShort(entry.localBytes, 28);
            int dataOffset = 30 + nameLength + extraLength;
            int compressedSize = checkedInt(readUnsignedInt(entry.rawCentralDirectory, 20), "compressed entry size");

            if (dataOffset + compressedSize > entry.localBytes.length) {
                throw new IOException("Truncated ZIP entry data: " + entry.getDisplayName());
            }

            byte[] compressed = Arrays.copyOfRange(entry.localBytes, dataOffset, dataOffset + compressedSize);

            if (entry.method == 0) {
                return compressed;
            }

            if (entry.method != 8) {
                throw new IOException("Unsupported ZIP compression method for " + entry.getDisplayName() + ": " + entry.method);
            }

            Inflater inflater = new Inflater(true);

            try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                copy(input, output);
                return output.toByteArray();
            } finally {
                inflater.end();
            }
        }

        /**
         * 将原始条目和新增钩子按原始目录编码方式写入新的 ZIP 文件。
         *
         * @param outputJar 临时输出 Jar 位置
         * @param appendedEntries 需要追加的钩子类条目
         * @throws IOException 写入失败或 ZIP 大小超出普通格式时抛出异常
         */
        private void write(Path outputJar, List<RawZipEntry> appendedEntries) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(source.length + 16384);
            output.write(prefix);
            List<RawZipEntry> allLocalEntries = new ArrayList<RawZipEntry>(localOrder);
            allLocalEntries.addAll(appendedEntries);

            for (RawZipEntry entry : allLocalEntries) {
                entry.outputLocalOffset = output.size();
                output.write(entry.localBytes);
            }

            int centralDirectoryOffset = output.size();
            List<RawZipEntry> allDirectoryEntries = new ArrayList<RawZipEntry>(entries);
            allDirectoryEntries.addAll(appendedEntries);

            if (allDirectoryEntries.size() > 0xFFFF) {
                throw new IOException("ZIP64 output is not supported.");
            }

            for (RawZipEntry entry : allDirectoryEntries) {
                byte[] centralDirectory = Arrays.copyOf(entry.rawCentralDirectory, entry.rawCentralDirectory.length);
                writeUnsignedInt(centralDirectory, 42, entry.outputLocalOffset); // 仅更新输出位置，保留原始名称字节和附加字段
                output.write(centralDirectory);
            }

            int centralDirectorySize = output.size() - centralDirectoryOffset;
            byte[] endOfDirectory = new byte[22];
            writeUnsignedInt(endOfDirectory, 0, END_OF_CENTRAL_DIRECTORY_SIGNATURE);
            writeUnsignedShort(endOfDirectory, 4, 0);
            writeUnsignedShort(endOfDirectory, 6, 0);
            writeUnsignedShort(endOfDirectory, 8, allDirectoryEntries.size());
            writeUnsignedShort(endOfDirectory, 10, allDirectoryEntries.size());
            writeUnsignedInt(endOfDirectory, 12, centralDirectorySize);
            writeUnsignedInt(endOfDirectory, 16, centralDirectoryOffset);
            writeUnsignedShort(endOfDirectory, 20, endComment.length);
            output.write(endOfDirectory);
            output.write(endComment);
            Files.write(outputJar, output.toByteArray());
        }

        /**
         * 从文件尾部查找 ZIP 中央目录结束记录。
         *
         * @param source 原始 ZIP 字节
         * @return 中央目录结束记录偏移
         * @throws IOException 未找到有效记录时抛出异常
         */
        private static int findEndOfCentralDirectory(byte[] source) throws IOException {
            int minimumOffset = Math.max(0, source.length - 65557);

            for (int offset = source.length - 22; offset >= minimumOffset; offset--) {
                if (readUnsignedInt(source, offset) == unsignedInt(END_OF_CENTRAL_DIRECTORY_SIGNATURE)
                        && offset + 22 + readUnsignedShort(source, offset + 20) == source.length) {
                    return offset;
                }
            }

            throw new IOException("ZIP end of central directory record was not found.");
        }
    }

    /**
     * 表示一个保留原始 ZIP 名称字节和目录记录的条目。
     */
    private static final class RawZipEntry {
        private final byte[] rawName;
        private byte[] rawCentralDirectory;
        private final int flags;
        private final int method;
        private final int localOffset;
        private byte[] localBytes;
        private int outputLocalOffset;

        /**
         * 创建原始中央目录条目模型。
         *
         * @param rawName 原始名称字节
         * @param rawCentralDirectory 原始中央目录记录字节
         * @param flags ZIP 通用标志位
         * @param method ZIP 压缩方式
         * @param localOffset 原始本地文件记录偏移
         */
        private RawZipEntry(byte[] rawName, byte[] rawCentralDirectory, int flags, int method, int localOffset) {
            this.rawName = rawName;
            this.rawCentralDirectory = rawCentralDirectory;
            this.flags = flags;
            this.method = method;
            this.localOffset = localOffset;
        }

        /**
         * 创建使用 UTF-8 名称的新 DEFLATE ZIP 条目。
         *
         * @param entryName 新条目名称
         * @param contents 新条目未压缩内容
         * @return 可追加到原始 Jar 的 ZIP 条目
         */
        private static RawZipEntry createNew(String entryName, byte[] contents) {
            byte[] rawName = entryName.getBytes(StandardCharsets.UTF_8);
            byte[] compressed = deflate(contents);
            long crc = calculateCrc(contents);
            byte[] localHeader = new byte[30 + rawName.length];
            writeUnsignedInt(localHeader, 0, LOCAL_FILE_HEADER_SIGNATURE);
            writeUnsignedShort(localHeader, 4, 20);
            writeUnsignedShort(localHeader, 6, UTF8_FLAG);
            writeUnsignedShort(localHeader, 8, 8);
            writeUnsignedShort(localHeader, 10, 0);
            writeUnsignedShort(localHeader, 12, 0);
            writeUnsignedInt(localHeader, 14, crc);
            writeUnsignedInt(localHeader, 18, compressed.length);
            writeUnsignedInt(localHeader, 22, contents.length);
            writeUnsignedShort(localHeader, 26, rawName.length);
            writeUnsignedShort(localHeader, 28, 0);
            System.arraycopy(rawName, 0, localHeader, 30, rawName.length);

            byte[] localBytes = concatenate(localHeader, compressed);
            byte[] centralDirectory = new byte[46 + rawName.length];
            writeUnsignedInt(centralDirectory, 0, CENTRAL_DIRECTORY_SIGNATURE);
            writeUnsignedShort(centralDirectory, 4, 20);
            writeUnsignedShort(centralDirectory, 6, 20);
            writeUnsignedShort(centralDirectory, 8, UTF8_FLAG);
            writeUnsignedShort(centralDirectory, 10, 8);
            writeUnsignedShort(centralDirectory, 12, 0);
            writeUnsignedShort(centralDirectory, 14, 0);
            writeUnsignedInt(centralDirectory, 16, crc);
            writeUnsignedInt(centralDirectory, 20, compressed.length);
            writeUnsignedInt(centralDirectory, 24, contents.length);
            writeUnsignedShort(centralDirectory, 28, rawName.length);
            writeUnsignedShort(centralDirectory, 30, 0);
            writeUnsignedShort(centralDirectory, 32, 0);
            writeUnsignedShort(centralDirectory, 34, 0);
            writeUnsignedShort(centralDirectory, 36, 0);
            writeUnsignedInt(centralDirectory, 38, 0);
            writeUnsignedInt(centralDirectory, 42, 0);
            System.arraycopy(rawName, 0, centralDirectory, 46, rawName.length);

            RawZipEntry entry = new RawZipEntry(rawName, centralDirectory, UTF8_FLAG, 8, -1);
            entry.localBytes = localBytes;
            return entry;
        }

        /**
         * 用新的未压缩内容替换现有条目的本地记录和目录校验数据。
         *
         * @param contents 替换后的类字节码
         * @throws IOException 原条目不能安全替换时抛出异常
         */
        private void replace(byte[] contents) throws IOException {
            if (method != 8 || (flags & DATA_DESCRIPTOR_FLAG) != 0) {
                throw new IOException("Target class has unsupported ZIP layout: " + getDisplayName());
            }

            int nameLength = readUnsignedShort(localBytes, 26);
            int extraLength = readUnsignedShort(localBytes, 28);
            int headerLength = 30 + nameLength + extraLength;

            if (headerLength > localBytes.length) {
                throw new IOException("Truncated local ZIP header: " + getDisplayName());
            }

            byte[] compressed = deflate(contents);
            long crc = calculateCrc(contents);
            byte[] localHeader = Arrays.copyOf(localBytes, headerLength);
            writeUnsignedInt(localHeader, 14, crc);
            writeUnsignedInt(localHeader, 18, compressed.length);
            writeUnsignedInt(localHeader, 22, contents.length);
            localBytes = concatenate(localHeader, compressed);
            rawCentralDirectory = Arrays.copyOf(rawCentralDirectory, rawCentralDirectory.length);
            writeUnsignedInt(rawCentralDirectory, 16, crc);
            writeUnsignedInt(rawCentralDirectory, 20, compressed.length);
            writeUnsignedInt(rawCentralDirectory, 24, contents.length);
        }

        /**
         * 返回仅用于错误信息的 UTF-8 尝试解码名称。
         *
         * @return 条目名称的可读表示
         */
        private String getDisplayName() {
            return new String(rawName, StandardCharsets.UTF_8);
        }
    }

    /**
     * 以无 zlib 头的 DEFLATE 格式压缩 ZIP 条目内容。
     *
     * @param contents 待压缩内容
     * @return 原始 DEFLATE 数据
     */
    private static byte[] deflate(byte[] contents) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(contents);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream(contents.length);
        byte[] buffer = new byte[8192];

        try {
            while (!deflater.finished()) {
                int written = deflater.deflate(buffer);
                output.write(buffer, 0, written);
            }

            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * 计算 ZIP 条目需要的 CRC-32 值。
     *
     * @param contents 条目未压缩内容
     * @return 无符号 CRC-32 值
     */
    private static long calculateCrc(byte[] contents) {
        CRC32 crc = new CRC32();
        crc.update(contents);
        return crc.getValue();
    }

    /**
     * 拼接两个字节数组。
     *
     * @param first 第一段数据
     * @param second 第二段数据
     * @return 拼接后的新数组
     */
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    /**
     * 从输入流复制全部数据到输出流。
     *
     * @param input 数据来源
     * @param output 数据去向
     * @throws IOException 读取或写入失败时抛出异常
     */
    private static void copy(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;

        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    /**
     * 从字节数组读取无符号 16 位小端整数。
     *
     * @param data 源字节数组
     * @param offset 数据偏移
     * @return 读取出的无符号数值
     */
    private static int readUnsignedShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    /**
     * 从字节数组读取无符号 32 位小端整数。
     *
     * @param data 源字节数组
     * @param offset 数据偏移
     * @return 读取出的无符号数值
     */
    private static long readUnsignedInt(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF)
                | (((long) data[offset + 1] & 0xFF) << 8)
                | (((long) data[offset + 2] & 0xFF) << 16)
                | (((long) data[offset + 3] & 0xFF) << 24);
    }

    /**
     * 将数值写入字节数组中的无符号 16 位小端位置。
     *
     * @param data 目标字节数组
     * @param offset 写入偏移
     * @param value 待写入数值
     */
    private static void writeUnsignedShort(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    /**
     * 将数值写入字节数组中的无符号 32 位小端位置。
     *
     * @param data 目标字节数组
     * @param offset 写入偏移
     * @param value 待写入数值
     */
    private static void writeUnsignedInt(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    /**
     * 将 ZIP 无符号 32 位值安全转换为 Java 数组索引。
     *
     * @param value 待转换数值
     * @param label 用于错误信息的数值名称
     * @return 可安全使用的整数值
     * @throws IOException 数值超过普通 ZIP 和内存实现范围时抛出异常
     */
    private static int checkedInt(long value, String label) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException("Unsupported ZIP " + label + ": " + value);
        }

        return (int) value;
    }

    /**
     * 将有符号 int 转换为无符号 32 位比较值。
     *
     * @param value 有符号 int 值
     * @return 无符号 32 位长整型表示
     */
    private static long unsignedInt(int value) {
        return value & 0xFFFFFFFFL;
    }
}
