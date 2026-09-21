package com.bluffgame.model;

import java.util.Comparator;

/**
 * A single physical card. {@code id} is unique across every deck in play so the
 * same rank/suit from two decks can be told apart.
 */
public record Card(int id, Rank rank, Suit suit) {

    /** Ace to king by rank, then spades/hearts/diamonds/clubs; jokers last. */
    public static final Comparator<Card> DISPLAY_ORDER =
            Comparator.comparing(Card::rank).thenComparing(Card::suit).thenComparingInt(Card::id);

    public Card {
        if (rank == null || suit == null) {
            throw new IllegalArgumentException("Card needs a rank and a suit");
        }
        if ((rank == Rank.JOKER) != (suit == Suit.NONE)) {
            throw new IllegalArgumentException("Jokers (and only jokers) have no suit");
        }
    }

    public static Card joker(int id) {
        return new Card(id, Rank.JOKER, Suit.NONE);
    }

    public boolean isJoker() {
        return rank == Rank.JOKER;
    }

    /** True when this card can legitimately be passed off as {@code declared}. Jokers match anything. */
    public boolean matches(Rank declared) {
        return isJoker() || rank == declared;
    }

    @Override
    public String toString() {
        return isJoker() ? "JOKER#" + id : rank.label() + suit.symbol() + "#" + id;
    }
}
