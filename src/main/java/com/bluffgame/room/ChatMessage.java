package com.bluffgame.room;

/** A chat line. {@code system} lines come from the game itself and carry no player. */
public record ChatMessage(String playerId, String nickname, String text, long at, boolean system) {

    static ChatMessage system(String text, long at) {
        return new ChatMessage(null, null, text, at, true);
    }
}
