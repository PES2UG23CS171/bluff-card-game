package com.bluffgame.room;

/** How the room layer talks back to browsers, kept abstract so the service can be tested without sockets. */
public interface Outbound {

    /** Route future messages for {@code playerId} through transport session {@code sessionId}. */
    void attach(String sessionId, String roomCode, String playerId);

    /** Deliver a JSON-serialisable message to a player, silently dropping it if they are offline. */
    void send(String playerId, Object message);
}
