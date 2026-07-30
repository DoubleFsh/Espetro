package org.espetro.team;

/**
 * 班组内火力组：A / B / C，每组上限 {@link #CAPACITY} 人。
 */
public enum Fireteam {
    A(0, "A", 0xFF2EE6D6),   // 青色
    B(1, "B", 0xFFB06CFF),   // 紫色（有别于小队长身份紫）
    C(2, "C", 0xFF4D9DFF);   // 蓝色

    public static final int CAPACITY = 3;
    public static final int COUNT = 3;

    private final int index;
    private final String label;
    private final int mapColor;

    Fireteam(int index, String label, int mapColor) {
        this.index = index;
        this.label = label;
        this.mapColor = mapColor;
    }

    public int index() {
        return index;
    }

    public String label() {
        return label;
    }

    /** ARGB 色条颜色（J 键成员卡最左侧火力组色块）。 */
    public int color() {
        return mapColor;
    }

    public static Fireteam fromIndex(int index) {
        Fireteam[] values = values();
        if (index < 0 || index >= values.length) {
            return A;
        }
        return values[index];
    }

    public static Fireteam fromNetwork(byte b) {
        return fromIndex(b);
    }

    public byte toNetwork() {
        return (byte) index;
    }
}
