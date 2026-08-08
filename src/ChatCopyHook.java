import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smoke 聊天复制的 Java 8 运行时钩子。
 */
public final class ChatCopyHook {
    private static final Object CONFIGURATION_LOCK = new Object(); // 保护配置和反射缓存的延迟初始化
    private static final String CONFIGURATION_DIRECTORY = "smoke"; // Smoke 独立配置目录名称
    private static final String CONFIGURATION_FILE_NAME = "chatcopy.json"; // 聊天复制配置文件名称
    private static final int LEFT_MOUSE_BUTTON = 0; // LWJGL 左键编号
    private static final int RIGHT_MOUSE_BUTTON = 1; // LWJGL 右键编号
    private static final int DEFAULT_MODIFIER_KEY = 56; // LWJGL 左 Alt 键码
    private static final Pattern FORMATTING_CODE = Pattern.compile("(?i)\\xA7[0-9A-FK-OR]"); // Minecraft 格式控制码

    private static volatile Configuration configuration;
    private static volatile File configurationFile;
    private static volatile Method keyboardStateMethod;
    private static volatile boolean keyboardLookupCompleted;
    private static volatile Object lastCandidateChat;
    private static volatile Object lastCandidateLine;

    /**
     * 禁止实例化纯静态运行时钩子。
     */
    private ChatCopyHook() {
    }

    /**
     * 处理聊天鼠标点击，并在命中复制手势时写入系统剪贴板。
     *
     * @param minecraft 当前 Minecraft 客户端对象
     * @param message 鼠标命中的聊天子组件对象
     * @param mouseButton 本次鼠标按键编号
     * @return 是否已成功复制并消费本次点击
     */
    public static boolean tryCopy(Object minecraft, Object message, int mouseButton) {
        Configuration activeConfiguration = getConfiguration(minecraft);

        if (!activeConfiguration.enabled || message == null || !isCopyGesture(activeConfiguration, mouseButton)) {
            return false;
        }

        String visibleText = stripFormatting(resolveCompleteMessageText(message)); // 优先复制完整逻辑消息而非自动换行片段

        if (visibleText.trim().isEmpty()) {
            return false;
        }

        return copyToClipboard(visibleText); // 仅在剪贴板写入成功后消费原始点击
    }

    /**
     * 记录聊天命中方法当前检查的精确渲染行。
     *
     * @param chat 当前 GuiNewChat 对象
     * @param line 当前命中的 ChatLine 对象
     */
    public static void rememberCandidateLine(Object chat, Object line) {
        lastCandidateChat = chat;
        lastCandidateLine = line;
    }

    /**
     * 根据精确渲染行从原始消息列表还原完整聊天文本。
     *
     * @param hoveredComponent 鼠标命中的聊天子组件
     * @return 完整逻辑消息，无法还原时返回当前渲染行文本
     */
    private static String resolveCompleteMessageText(Object hoveredComponent) {
        Object chat = lastCandidateChat;
        Object drawnLine = lastCandidateLine;
        String fallbackText = getComponentText(hoveredComponent);

        if (chat == null || drawnLine == null) {
            return fallbackText;
        }

        Object drawnComponent = readField(drawnLine, "lineString");
        Object updateCounter = readField(drawnLine, "updateCounterCreated");

        if (!(updateCounter instanceof Number) || drawnComponent == null) {
            return fallbackText;
        }

        String drawnText = getComponentText(drawnComponent);
        Object originalLines = readField(chat, "chatLines");

        if (!(originalLines instanceof List<?>)) {
            return drawnText.isEmpty() ? fallbackText : drawnText;
        }

        int drawnUpdateCounter = ((Number) updateCounter).intValue();

        for (Object originalLine : (List<?>) originalLines) {
            Object originalUpdateCounter = readField(originalLine, "updateCounterCreated");

            if (!(originalUpdateCounter instanceof Number)
                    || ((Number) originalUpdateCounter).intValue() != drawnUpdateCounter) {
                continue;
            }

            Object originalComponent = readField(originalLine, "lineString");
            String originalText = getComponentText(originalComponent);

            if (!drawnText.isEmpty() && originalText.contains(drawnText)) {
                return originalText;
            }
        }

        return drawnText.isEmpty() ? fallbackText : drawnText;
    }

    /**
     * 调用 IChatComponent 的无格式文本接口。
     *
     * @param component 聊天组件对象
     * @return 不含样式的可见文本，读取失败时返回空字符串
     */
    private static String getComponentText(Object component) {
        if (component == null) {
            return "";
        }

        try {
            Method method = component.getClass().getMethod("getUnformattedText");
            Object value = method.invoke(component);
            return value instanceof String ? (String) value : "";
        } catch (ReflectiveOperationException exception) {
            return "";
        }
    }

    /**
     * 获取与当前游戏目录绑定的聊天复制配置。
     *
     * @param minecraft 当前 Minecraft 客户端对象
     * @return 已加载或新建的配置对象
     */
    private static Configuration getConfiguration(Object minecraft) {
        File gameDirectory = getGameDirectory(minecraft);
        File targetDirectory = new File(gameDirectory, CONFIGURATION_DIRECTORY);
        File targetFile = new File(targetDirectory, CONFIGURATION_FILE_NAME);

        synchronized (CONFIGURATION_LOCK) {
            if (configuration != null && targetFile.equals(configurationFile)) {
                return configuration;
            }

            configuration = loadConfiguration(targetFile);
            configurationFile = targetFile;
            return configuration;
        }
    }

    /**
     * 从 Minecraft 对象中读取游戏目录。
     *
     * @param minecraft 当前 Minecraft 客户端对象
     * @return 当前游戏目录，读取失败时使用进程目录
     */
    private static File getGameDirectory(Object minecraft) {
        Object value = readField(minecraft, "mcDataDir");
        return value instanceof File ? (File) value : new File(".");
    }

    /**
     * 从 JSON 文件加载配置，并在首次使用时写入默认值。
     *
     * @param targetFile 配置文件位置
     * @return 可供当前会话使用的配置对象
     */
    private static Configuration loadConfiguration(File targetFile) {
        Configuration defaults = new Configuration(true, true, DEFAULT_MODIFIER_KEY); // 默认开启右键与左 Alt 复制

        if (!targetFile.isFile()) {
            writeDefaultConfiguration(targetFile, defaults);
            return defaults;
        }

        try {
            String json = new String(Files.readAllBytes(targetFile.toPath()), StandardCharsets.UTF_8);
            boolean enabled = readBoolean(json, "enabled", defaults.enabled);
            boolean rightClick = readBoolean(json, "rightClick", defaults.rightClick);
            int modifierKey = readInteger(json, "modifierKey", defaults.modifierKey);
            return new Configuration(enabled, rightClick, modifierKey);
        } catch (IOException exception) {
            return defaults;
        }
    }

    /**
     * 将首次运行需要的默认配置写入游戏目录。
     *
     * @param targetFile 配置文件位置
     * @param defaults 默认配置内容
     */
    private static void writeDefaultConfiguration(File targetFile, Configuration defaults) {
        File parentDirectory = targetFile.getParentFile();

        if (!parentDirectory.isDirectory() && !parentDirectory.mkdirs()) {
            return;
        }

        String json = "{\n"
                + "  \"enabled\": " + defaults.enabled + ",\n" // 是否默认启用聊天复制
                + "  \"rightClick\": " + defaults.rightClick + ",\n" // 是否允许右键直接复制
                + "  \"modifierKey\": " + defaults.modifierKey + "\n" // 组合键使用的 LWJGL 键码
                + "}\n";

        try {
            Files.write(targetFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            // 配置写入失败时继续使用内存中的默认值。
        }
    }

    /**
     * 读取 JSON 中的布尔配置值。
     *
     * @param json JSON 原始内容
     * @param property 配置属性名称
     * @param defaultValue 属性缺失或无效时的默认值
     * @return 解析后的布尔值
     */
    private static boolean readBoolean(String json, String property, boolean defaultValue) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(property) + "\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : defaultValue;
    }

    /**
     * 读取 JSON 中的整数配置值。
     *
     * @param json JSON 原始内容
     * @param property 配置属性名称
     * @param defaultValue 属性缺失或无效时的默认值
     * @return 解析后的整数值
     */
    private static int readInteger(String json, String property, int defaultValue) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(property) + "\\\"\\s*:\\s*(-?\\d+)");
        Matcher matcher = pattern.matcher(json);

        if (!matcher.find()) {
            return defaultValue;
        }

        try {
            int value = Integer.parseInt(matcher.group(1));
            return value >= 0 ? value : defaultValue;
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    /**
     * 判断本次鼠标点击是否属于用户配置的复制操作。
     *
     * @param activeConfiguration 当前配置
     * @param mouseButton 本次鼠标按键编号
     * @return 是否应当尝试复制聊天组件
     */
    private static boolean isCopyGesture(Configuration activeConfiguration, int mouseButton) {
        if (mouseButton == RIGHT_MOUSE_BUTTON) {
            return activeConfiguration.rightClick;
        }

        return mouseButton == LEFT_MOUSE_BUTTON && isModifierKeyDown(activeConfiguration.modifierKey);
    }

    /**
     * 通过 LWJGL 键盘状态查询用户配置的组合键。
     *
     * @param keyCode LWJGL 键码
     * @return 该按键当前是否按下
     */
    private static boolean isModifierKeyDown(int keyCode) {
        Method stateMethod = getKeyboardStateMethod();

        if (stateMethod == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(stateMethod.invoke(null, Integer.valueOf(keyCode)));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    /**
     * 延迟解析 LWJGL 键盘 API，避免补丁类编译时依赖客户端 Jar。
     *
     * @return 键盘状态查询方法，无法获取时返回 null
     */
    private static Method getKeyboardStateMethod() {
        if (keyboardLookupCompleted) {
            return keyboardStateMethod;
        }

        synchronized (CONFIGURATION_LOCK) {
            if (keyboardLookupCompleted) {
                return keyboardStateMethod;
            }

            try {
                Class<?> keyboardClass = Class.forName("org.lwjgl.input.Keyboard");
                keyboardStateMethod = keyboardClass.getMethod("isKeyDown", Integer.TYPE);
            } catch (ReflectiveOperationException exception) {
                keyboardStateMethod = null;
            }

            keyboardLookupCompleted = true;
            return keyboardStateMethod;
        }
    }

    /**
     * 使用客户端自带剪贴板方法写入文本。
     *
     * @param text 待复制的纯文本
     * @return 是否成功写入系统剪贴板
     */
    private static boolean copyToClipboard(String text) {
        try {
            Class<?> guiScreenClass = Class.forName("net.minecraft.client.gui.GuiScreen");
            Method method = guiScreenClass.getMethod("setClipboardString", String.class);
            method.invoke(null, text);
            return true;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    /**
     * 读取对象或父类中的字段值。
     *
     * @param target 目标对象
     * @param fieldName 字段名称
     * @return 字段值，读取失败时返回 null
     */
    private static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }

        Field field = findField(target.getClass(), fieldName);

        if (field == null) {
            return null;
        }

        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    /**
     * 在类层级中定位可访问的字段。
     *
     * @param type 起始类
     * @param fieldName 字段名称
     * @return 已设为可访问的字段，未找到时返回 null
     */
    private static Field findField(Class<?> type, String fieldName) {
        Class<?> currentType = type;

        while (currentType != null) {
            try {
                Field field = currentType.getDeclaredField(fieldName);
                field.setAccessible(true); // 读取 Smoke 私有聊天列表和聊天行字段
                return field;
            } catch (NoSuchFieldException exception) {
                currentType = currentType.getSuperclass();
            } catch (SecurityException exception) {
                return null;
            }
        }

        return null;
    }

    /**
     * 去除意外残留的 Minecraft 格式控制码。
     *
     * @param text 原始聊天文本
     * @return 可安全写入剪贴板的纯可见文本
     */
    private static String stripFormatting(String text) {
        return text == null ? "" : FORMATTING_CODE.matcher(text).replaceAll("");
    }

    /**
     * 保存聊天复制的不可变配置值。
     */
    private static final class Configuration {
        private final boolean enabled;
        private final boolean rightClick;
        private final int modifierKey;

        /**
         * 创建不可变的聊天复制配置。
         *
         * @param enabled 是否启用聊天复制
         * @param rightClick 是否启用右键复制
         * @param modifierKey 左键复制所需的 LWJGL 键码
         */
        private Configuration(boolean enabled, boolean rightClick, int modifierKey) {
            this.enabled = enabled;
            this.rightClick = rightClick;
            this.modifierKey = modifierKey;
        }
    }
}
