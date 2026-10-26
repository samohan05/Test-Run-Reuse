package com.intland.codebeamer.wiki.plugins.streamreuse;
public enum ReuseMode { LATEST, ALL;
    public static ReuseMode parse(String value) {
        if (value == null || value.trim().isEmpty()) return LATEST;
        for (ReuseMode mode : values()) if (mode.name().equalsIgnoreCase(value.trim())) return mode;
        throw new IllegalArgumentException("reuseMode must be 'latest' or 'all'");
    }
}
