package com.bluffgame.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Suit {
    SPADES("S", "♠", false),
    HEARTS("H", "♥", true),
    DIAMONDS("D", "♦", true),
    CLUBS("C", "♣", false),
    /** Used by jokers only. */
    NONE("N", "", false);

    private final String code;
    private final String symbol;
    private final boolean red;

    Suit(String code, String symbol, boolean red) {
        this.code = code;
        this.symbol = symbol;
        this.red = red;
    }

    /** Single-letter code sent over the wire. */
    @JsonValue
    public String code() {
        return code;
    }

    public String symbol() {
        return symbol;
    }

    public boolean isRed() {
        return red;
    }

    /** The four real suits, in display order. */
    public static Suit[] standard() {
        return new Suit[] {SPADES, HEARTS, DIAMONDS, CLUBS};
    }
}
