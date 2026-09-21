package com.bluffgame.model;

/**
 * Host-chosen rules for a game.
 *
 * @param decks             how many 52-card decks are shuffled together
 * @param cardsPerPlayer    how many cards each player is dealt from the shuffled pile
 * @param jokers            whether each deck contributes two wild jokers
 * @param callWindowSeconds minimum time after a play during which the next player must wait,
 *                          so everyone gets a chance to call a bluff (0 disables the wait)
 * @param turnSeconds       how long a player has to act on their turn before they are passed
 *                          automatically (0 disables the timer)
 */
public record GameSettings(int decks, int cardsPerPlayer, boolean jokers, int callWindowSeconds, int turnSeconds) {

    public static final int MAX_DECKS = 8;
    public static final int MAX_CALL_WINDOW_SECONDS = 30;
    public static final int MIN_TURN_SECONDS = 10;
    public static final int MAX_TURN_SECONDS = 300;

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
        if (callWindowSeconds < 0 || callWindowSeconds > MAX_CALL_WINDOW_SECONDS) {
            throw new IllegalArgumentException("Call window must be between 0 and " + MAX_CALL_WINDOW_SECONDS + " seconds");
        }
        if (turnSeconds < 0 || turnSeconds > MAX_TURN_SECONDS) {
            throw new IllegalArgumentException("Turn timer must be between 0 and " + MAX_TURN_SECONDS + " seconds");
        }
        if (turnSeconds != 0 && turnSeconds < MIN_TURN_SECONDS) {
            throw new IllegalArgumentException("Turn timer must be at least " + MIN_TURN_SECONDS + " seconds, or 0 to switch it off");
        }
        if (turnSeconds != 0 && turnSeconds <= callWindowSeconds) {
            throw new IllegalArgumentException("Turn timer must be longer than the call window");
        }
    }

    public static GameSettings defaults() {
        return new GameSettings(1, 8, true, 5, 30);
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
