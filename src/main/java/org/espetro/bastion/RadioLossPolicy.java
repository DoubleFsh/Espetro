package org.espetro.bastion;

/**
 * 何时将 Radio 摧毁记为「电台损失」（扣兵力并广播 {@code [Radio] 已被摧毁}）。
 * <p>
 * 不计损失：己方工兵铲拆除、指挥收起、战局结束静默清理。
 * 爆炸 / 投射物 / 核心方块消失 / 敌方拆除一律计损失——即使该格已登记为工事。
 */
public final class RadioLossPolicy {

    private RadioLossPolicy() {
    }

    /**
     * 爆炸 affected 列表含电台核心时必须记损失。
     * {@code indexedAsFortification} 不得跳过：工事爆炸减伤 + 每格只结算一次无法打穿电台结构 HP。
     */
    public static boolean explosionScoresRadioLoss(boolean radioCoreInAffectedBlocks,
                                                   boolean indexedAsFortification) {
        return radioCoreInAffectedBlocks;
    }

    /**
     * 建成电台被摧毁时是否扣兵力。
     */
    public static boolean deductManpower(boolean isRadio,
                                         boolean friendlyShovelDismantle,
                                         boolean silentMatchEnd) {
        return isRadio && !friendlyShovelDismantle && !silentMatchEnd;
    }
}
