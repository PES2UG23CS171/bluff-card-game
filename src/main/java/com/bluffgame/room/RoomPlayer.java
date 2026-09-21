package com.bluffgame.room;

import java.time.Instant;

/** A person in a room. Survives page reloads through {@code token}, which only that person's browser knows. */
public final class RoomPlayer {

    private final String id;
    private final String token;
    private final String nickname;
    private boolean connected = true;
    private String sessionId;
    private Instant disconnectedAt;

    RoomPlayer(String id, String token, String nickname, String sessionId) {
        this.id = id;
        this.token = token;
        this.nickname = nickname;
        this.sessionId = sessionId;
    }

    public String id() {
        return id;
    }

    public String token() {
        return token;
    }

    public String nickname() {
        return nickname;
    }

    public boolean connected() {
        return connected;
    }

    public String sessionId() {
        return sessionId;
    }

    public Instant disconnectedAt() {
        return disconnectedAt;
    }

    void connect(String newSessionId) {
        this.sessionId = newSessionId;
        this.connected = true;
        this.disconnectedAt = null;
    }

    void disconnect(Instant when) {
        this.connected = false;
        this.disconnectedAt = when;
    }
}
