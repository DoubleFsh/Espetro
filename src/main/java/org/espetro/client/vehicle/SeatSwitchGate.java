package org.espetro.client.vehicle;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.espetro.network.NetworkManager;
import org.espetro.network.SeatSwitchReadyPacket;
import org.espetro.vehicle.SbwVehicleSeatResolver;
import org.espetro.vehicle.VehicleInteractionConfig;
import org.lwjgl.glfw.GLFW;

/**
 * Seat-switch channel for SBW (hard-coded Shift + number keys 1-9).
 * Progress fills while Shift is held. Only after the channel completes
 * ({@link #isArmed()}) may the client send the SBW {@code ChangeVehicleSeatMessage}.
 */
public final class SeatSwitchGate {

    private static int switchTicks;
    private static boolean armed;
    private static boolean registered;
    private static long armedUntilClientTick;
    /** 上次已发送换座的座位索引；-1 = 无。用于数字键边沿检测（按住不重复触发）。 */
    private static int lastSentSeatIndex = -1;

    private SeatSwitchGate() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(SeatSwitchGate.class);
    }

    /** True for a short window after the channel completes. */
    public static boolean isArmed() {
        if (!armed) {
            return VehicleInteractionConfig.seatSwitchDelayTicks() <= 0;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        return mc.level.getGameTime() <= armedUntilClientTick;
    }

    /** Consume the one-shot arm after a seat change is dispatched. */
    public static void consumeArmed() {
        armed = false;
        switchTicks = 0;
        armedUntilClientTick = 0L;
        if (VehicleInteractionState.kind() == VehicleInteractionKind.SEAT_SWITCH) {
            VehicleInteractionState.clear();
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.screen != null) {
            reset();
            return;
        }
        if (mc.player.getVehicle() == null
            || !SbwVehicleSeatResolver.isSupportedVehicle(mc.player.getVehicle())) {
            reset();
            return;
        }

        int delay = VehicleInteractionConfig.seatSwitchDelayTicks();

        // 换座读条只由 Shift+数字键 组合驱动：单独按住 Shift（潜行）不进入换座流程，
        // 也不触发任何换座消息，避免与 SBW 原版路径冲突导致客户端崩溃。
        int seatIndex = seatIndexForPressedNumberKey(mc);
        if (seatIndex < 0) {
            if (armed && mc.level != null && mc.level.getGameTime() > armedUntilClientTick) {
                reset();
            } else if (!armed) {
                reset();
            }
            lastSentSeatIndex = -1;
            return;
        }

        if (delay <= 0) {
            // 无读条：Shift+数字键 按下即换座（边沿检测防重复触发）。
            if (seatIndex != lastSentSeatIndex) {
                lastSentSeatIndex = seatIndex;
                sendSbwChangeSeatMessage(seatIndex);
            }
            VehicleInteractionState.setSeatSwitch(1f);
            return;
        }

        if (armed) {
            // 读条完成：边沿检测，同一数字键持续按住不重复触发。
            if (seatIndex != lastSentSeatIndex) {
                lastSentSeatIndex = seatIndex;
                consumeArmed();
                sendSbwChangeSeatMessage(seatIndex);
            }
            VehicleInteractionState.setSeatSwitch(1f);
            return;
        }

        switchTicks++;
        float progress = Math.min(1f, switchTicks / (float) delay);
        VehicleInteractionState.setSeatSwitch(progress);
        if (switchTicks >= delay) {
            armed = true;
            armedUntilClientTick = mc.level.getGameTime() + 40L;
            NetworkManager.NET.sendToServer(new SeatSwitchReadyPacket());
            VehicleInteractionState.setSeatSwitch(1f);
        }
    }

    /**
     * 硬编码映射：Shift 按住 + 数字键 1-9 → 座位 0-8。
     * 使用原始 GLFW 键码，不依赖 SBW 的 CHANGE_SEAT 按键绑定配置。
     *
     * @return 座位索引；未按数字键或未按 Shift 时返回 -1
     */
    private static int seatIndexForPressedNumberKey(Minecraft mc) {
        if (!mc.options.keyShift.isDown()) {
            return -1;
        }
        long window = mc.getWindow().getWindow();
        for (int i = 0; i < 9; i++) {
            if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_1 + i) == GLFW.GLFW_PRESS) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 发送 SBW 原版 {@code ChangeVehicleSeatMessage(index)}（反射构造，经 SBW 自己的
     * {@code MinecraftUtil.sendPacketToServer} 通道发送，与 SBW 原版 Shift+热键栏
     * 触发路径完全一致）。服务端仍由 Espetro 的 {@code ChangeVehicleSeatMessageMixin}
     * 做读条 token 与组员座位策略校验后，由 SBW 原版 handler 权威执行换座并广播乘客同步。
     */
    private static void sendSbwChangeSeatMessage(int seatIndex) {
        try {
            Class<?> messageClass = Class.forName(
                "com.atsuishio.superbwarfare.network.message.send.ChangeVehicleSeatMessage",
                false, SeatSwitchGate.class.getClassLoader());
            Object message = messageClass.getConstructor(int.class).newInstance(seatIndex);
            Class<?> utilClass = Class.forName(
                "com.atsuishio.superbwarfare.tools.MinecraftUtil",
                false, SeatSwitchGate.class.getClassLoader());
            java.lang.reflect.Method send = utilClass.getMethod("sendPacketToServer", Object.class);
            send.invoke(null, message);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // SBW 未加载或消息构造失败：忽略，维持现状
        }
    }

    private static void reset() {
        if ((switchTicks > 0 || armed)
            && VehicleInteractionState.kind() == VehicleInteractionKind.SEAT_SWITCH) {
            VehicleInteractionState.clear();
        }
        switchTicks = 0;
        armed = false;
        armedUntilClientTick = 0L;
        lastSentSeatIndex = -1;
    }
}
