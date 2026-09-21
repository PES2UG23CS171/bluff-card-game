package com.bluffgame.room;

import com.bluffgame.engine.BluffGame;
import com.bluffgame.model.GameSettings;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** A lobby plus, once started, the game being played in it. Guarded by synchronising on the room. */
public final class Room {

    static final int CHAT_HISTORY = 100;

    private final String code;
    private final List<RoomPlayer> players = new ArrayList<>();
    private final Deque<ChatMessage> chat = new ArrayDeque<>();
    private String hostId;
    private GameSettings settings = GameSettings.defaults();
    private BluffGame game;
    private Instant lastActivity;

    Room(String code, Instant createdAt) {
        this.code = code;
        this.lastActivity = createdAt;
    }

    public String code() {
        return code;
    }

    public String hostId() {
        return hostId;
    }

    void setHostId(String hostId) {
        this.hostId = hostId;
    }

    public boolean isHost(String playerId) {
        return playerId != null && playerId.equals(hostId);
    }

    public GameSettings settings() {
        return settings;
    }

    void setSettings(GameSettings settings) {
        this.settings = settings;
    }

    public BluffGame game() {
        return game;
    }

    void setGame(BluffGame game) {
        this.game = game;
    }

    public RoomPhase phase() {
        if (game == null) {
            return RoomPhase.LOBBY;
        }
        return game.phase() == BluffGame.Phase.GAME_OVER ? RoomPhase.GAME_OVER : RoomPhase.PLAYING;
    }

    public List<RoomPlayer> players() {
        return Collections.unmodifiableList(players);
    }

    List<RoomPlayer> mutablePlayers() {
        return players;
    }

    public Optional<RoomPlayer> player(String playerId) {
        return players.stream().filter(p -> p.id().equals(playerId)).findFirst();
    }

    Optional<RoomPlayer> playerByToken(String token) {
        return token == null ? Optional.empty() : players.stream().filter(p -> p.token().equals(token)).findFirst();
    }

    public long connectedCount() {
        return players.stream().filter(RoomPlayer::connected).count();
    }

    /** Whether {@code playerId} has a seat in the current game (spectators and late joiners do not). */
    public boolean isSeated(String playerId) {
        return game != null && game.seat(playerId) != null && !game.seat(playerId).left();
    }

    public List<ChatMessage> chatHistory() {
        return List.copyOf(chat);
    }

    void addChat(ChatMessage message) {
        chat.addLast(message);
        while (chat.size() > CHAT_HISTORY) {
            chat.removeFirst();
        }
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    void touch(Instant now) {
        lastActivity = now;
    }
}
