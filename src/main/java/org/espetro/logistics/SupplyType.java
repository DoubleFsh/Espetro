package org.espetro.logistics;

import javax.annotation.Nullable;

public enum SupplyType {
    CONSTRUCTION("construction"),
    AMMUNITION("ammunition");

    private final String id;

    SupplyType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Nullable
    public static SupplyType fromId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (SupplyType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }
}
