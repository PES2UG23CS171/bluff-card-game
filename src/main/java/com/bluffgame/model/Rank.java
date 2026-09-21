package com.bluffgame.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public enum Rank {
    ACE("A"),
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    FIVE("5"),
    SIX("6"),
    SEVEN("7"),
    EIGHT("8"),
    NINE("9"),
    TEN("10"),
    JACK("J"),
    QUEEN("Q"),
    KING("K"),
    /** Wild card: counts as whatever rank was declared. Can never be declared itself. */
    JOKER("JOKER");

    private final String label;

    Rank(String label) {
        this.label = label;
    }

    /** Short label sent over the wire and printed on cards. */
    @JsonValue
    public String label() {
        return label;
    }

    /** Whether a player may announce this rank when putting cards in the pot. */
    public boolean isDeclarable() {
        return this != JOKER;
    }

    /** The thirteen ranks a player can declare, ace to king. */
    public static List<Rank> declarable() {
        return Arrays.stream(values()).filter(Rank::isDeclarable).toList();
    }

    /** Parses a label such as {@code "K"}, {@code "10"} or {@code "a"}; {@code "1"} is accepted for the ace. */
    public static Rank fromLabel(String label) {
        if (label == null) {
            throw new IllegalArgumentException("Rank is missing");
        }
        String wanted = label.trim().toUpperCase(Locale.ROOT);
        if (wanted.equals("1")) {
            return ACE;
        }
        for (Rank rank : values()) {
            if (rank.label.equals(wanted)) {
                return rank;
            }
        }
        throw new IllegalArgumentException("Unknown rank: " + label);
    }
}
