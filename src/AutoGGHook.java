import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 修复 Smoke 遗漏 Hypixel 结算规则的 Java 8 AutoGG 运行时钩子。
 */
public final class AutoGGHook {
    private static final Object SEND_LOCK = new Object(); // 保护结算消息去重状态
    private static final long SEND_COOLDOWN_MILLIS = 2500L; // 同一场结算提示的最短发送间隔
    private static final String DEFAULT_MESSAGE = "GG"; // 未配置自定义文本时使用的默认消息
    private static final String AUTOGG_COMMAND_CLASS = "icu.hanabi.smoke.command.commands.AutoGGCommand"; // Smoke 原有 AutoGG 文本列表类

    private static final Random RANDOM = new Random();
    private static volatile long lastSendTime;

    /**
     * 禁止实例化纯静态 AutoGG 运行时钩子。
     */
    private AutoGGHook() {
    }

    /**
     * 检测客户端遗漏的 Hypixel 结算提示并发送 AutoGG 文本。
     *
     * @param module Smoke 的 AutoGG 模块对象
     * @param event 收到的 EventChat 事件对象
     * @return 是否已识别为 Hypixel 结算提示
     */
    public static boolean trySend(Object module, Object event) {
        String message = getEventMessage(event);

        if (message.isEmpty() || !matchesHypixelEndMessage(module, message)) {
            return false;
        }

        if (!claimSendSlot()) {
            return true;
        }

        if (!sendChatMessage(getConfiguredMessage())) {
            clearSendSlot();
        }

        return true;
    }

    /**
     * 从 EventChat 读取客户端提供的未格式化聊天文本。
     *
     * @param event 收到的 EventChat 事件对象
     * @return 聊天文本，读取失败时返回空字符串
     */
    private static String getEventMessage(Object event) {
        if (event == null) {
            return "";
        }

        try {
            Method method = event.getClass().getMethod("getMessage");
            Object value = method.invoke(event);
            return value instanceof String ? (String) value : "";
        } catch (ReflectiveOperationException exception) {
            return "";
        }
    }

    /**
     * 使用 Smoke 模块内置的 Hypixel 结算文本数组进行匹配。
     *
     * @param module Smoke 的 AutoGG 模块对象
     * @param message 收到的聊天文本
     * @return 是否命中任一 Hypixel 结算提示
     */
    private static boolean matchesHypixelEndMessage(Object module, String message) {
        Object value = readField(module, "Hypixel");

        if (!(value instanceof String[])) {
            return false;
        }

        for (String trigger : (String[]) value) {
            if (trigger != null && !trigger.isEmpty() && message.contains(trigger)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 取得用户在 Smoke 原有 AutoGG 命令中保存的随机消息。
     *
     * @return 选中的自定义消息，列表为空时返回 GG
     */
    private static String getConfiguredMessage() {
        Object value = readStaticField(AUTOGG_COMMAND_CLASS, "AutoGGText");

        if (!(value instanceof List<?>)) {
            return DEFAULT_MESSAGE;
        }

        List<String> messages = new ArrayList<String>();

        for (Object candidate : (List<?>) value) {
            if (candidate instanceof String && !((String) candidate).trim().isEmpty()) {
                messages.add((String) candidate);
            }
        }

        if (messages.isEmpty()) {
            return DEFAULT_MESSAGE;
        }

        return messages.get(RANDOM.nextInt(messages.size())); // 使用完整列表范围，修复原实现遗漏最后一项的问题
    }

    /**
     * 占用一次结算消息发送时段，避免一局结束的多条公告重复发送。
     *
     * @return 当前结算提示是否允许发送
     */
    private static boolean claimSendSlot() {
        long currentTime = System.currentTimeMillis();

        synchronized (SEND_LOCK) {
            if (currentTime - lastSendTime < SEND_COOLDOWN_MILLIS) {
                return false;
            }

            lastSendTime = currentTime;
            return true;
        }
    }

    /**
     * 在无法发送聊天消息时释放已占用的去重时段。
     */
    private static void clearSendSlot() {
        synchronized (SEND_LOCK) {
            lastSendTime = 0L;
        }
    }

    /**
     * 通过 Minecraft 当前玩家对象发送普通聊天消息。
     *
     * @param message 待发送的 AutoGG 文本
     * @return 是否成功调用客户端聊天发送方法
     */
    private static boolean sendChatMessage(String message) {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Method getMinecraft = minecraftClass.getMethod("getMinecraft");
            Object minecraft = getMinecraft.invoke(null);
            Object player = readField(minecraft, "thePlayer");

            if (player == null) {
                return false;
            }

            Method sendChatMessage = player.getClass().getMethod("sendChatMessage", String.class);
            sendChatMessage.invoke(player, message);
            return true;
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    /**
     * 读取指定类中的静态字段值。
     *
     * @param className 目标类全名
     * @param fieldName 字段名称
     * @return 字段值，读取失败时返回 null
     */
    private static Object readStaticField(String className, String fieldName) {
        try {
            Class<?> targetClass = Class.forName(className);
            Field field = findField(targetClass, fieldName);
            return field == null ? null : field.get(null);
        } catch (ReflectiveOperationException exception) {
            return null;
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
     * 在类层级中定位可访问字段。
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
                field.setAccessible(true); // 读取 Smoke 私有模块字段与自定义消息列表
                return field;
            } catch (NoSuchFieldException exception) {
                currentType = currentType.getSuperclass();
            } catch (SecurityException exception) {
                return null;
            }
        }

        return null;
    }
}
