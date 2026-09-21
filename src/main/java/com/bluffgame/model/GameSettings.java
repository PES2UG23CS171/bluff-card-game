package com.bluffgame.model;

/**
 * Host-chosen rules for a game.
 *
 * @param decks             how many 52-card decks are shuffled together
 * @param cardsPerPlayer    how many cards each player is dealt from the shuffled pile
 * @param jokers            whether each deck contributes two wild jokers
 * @param rankMode          whether the rank is fixed per round or declared per play
 * @param callWindowSeconds minimum time after a play during which the next player must wait,
 *                          so everyone gets a chance to call a bluff (0 disables the wait)
 */
public record GameSettings(int decks, int cardsPerPlayer, boolean jokers, RankMode rankMode, int callWindowSeconds) {

    public static final int MAX_DECKS = 8;
    public static final int MAX_CALL_WINDOW_SECONDS = 30;

    public GameSettings {
        if (decks < 1 || decks > MAX_DECKS) {
            throw new IllegalArgumentException("Decks must be between 1 and " + MAX_DECKS);
        }
        if (cardsPerPlayer < 1) {
            throw new IllegalArgumentException("Each player needs at least one card");
        }
        if (cardsPerPlayer > DeckFactory.size(decks, jokers)) {
            throw new IllegalArgumentException("Cards per player cannot exceed the "
                    + DeckFactory.size(decks, jokers) + " cards in " + decks + " deck(s)");
        }
        if (rankMode == null) {
            throw new IllegalArgumentException("Rank mode is required");
        }
        if (callWindowSeconds < 0 || callWindowSeconds > MAX_CALL_WINDOW_SECONDS) {
            throw new IllegalArgumentException("Call window must be between 0 and " + MAX_CALL_WINDOW_SECONDS + " seconds");
        }
    }

    public static GameSettings defaults() {
        return new GameSettings(1, 8, true, RankMode.ROUND, 5);
    }

    /** Cards available in the shuffled pile. */
    public int totalCards() {
        return DeckFactory.size(decks, jokers);
    }

    /** Largest {@code cardsPerPlayer} that still lets {@code players} players be dealt. */
    public int maxCardsPerPlayer(int players) {
        return players < 1 ? totalCards() : totalCards() / players;
    }

    /** Whether the pile holds enough cards for {@code players} players. */
    public boolean canDeal(int players) {
        return players >= 1 && cardsPerPlayer * players <= totalCards();
    }
}
