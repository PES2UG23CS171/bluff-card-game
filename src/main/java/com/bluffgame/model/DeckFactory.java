package com.bluffgame.model;

import java.util.ArrayList;
import java.util.List;

/** Builds the combined, unshuffled pile of cards for a game. */
public final class DeckFactory {

    public static final int CARDS_PER_DECK = 52;
    public static final int JOKERS_PER_DECK = 2;

    private DeckFactory() {
    }

    /** Number of cards {@code decks} decks contain, with or without jokers. */
    public static int size(int decks, boolean jokers) {
        return decks * (CARDS_PER_DECK + (jokers ? JOKERS_PER_DECK : 0));
    }

    /** All cards of {@code decks} decks in a fixed order, ids running from 0. */
    public static List<Card> build(int decks, boolean jokers) {
        if (decks < 1) {
            throw new IllegalArgumentException("At least one deck is needed");
        }
        List<Card> cards = new ArrayList<>(size(decks, jokers));
        int id = 0;
        for (int d = 0; d < decks; d++) {
            for (Suit suit : Suit.standard()) {
                for (Rank rank : Rank.declarable()) {
                    cards.add(new Card(id++, rank, suit));
                }
            }
            if (jokers) {
                for (int j = 0; j < JOKERS_PER_DECK; j++) {
                    cards.add(Card.joker(id++));
                }
            }
        }
        return cards;
    }
}
