package com.bluffgame.engine;

/** A rule violation or an action that is not allowed right now. The message is safe to show to players. */
public class GameException extends RuntimeException {

    public GameException(String message) {
        super(message);
    }
}
