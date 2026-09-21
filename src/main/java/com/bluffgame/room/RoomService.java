package com.bluffgame.room;

import com.bluffgame.engine.BluffGame;
import com.bluffgame.engine.GameEvent;
import com.bluffgame.engine.GameException;
import com.bluffgame.model.GameSettings;
import com.bluffgame.model.Rank;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Owns every room: who is in it, the chat, and the game in progress. All room mutations happen
 * while holding the room's monitor, and every mutation ends by pushing fresh state to everyone.
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_NICKNAME = 16;
    private static final int MAX_CHAT = 300;

    static final Duration LOBBY_DISCONNECT_GRACE = Duration.ofSeconds(60);
    static final Duration EMPTY_ROOM_TTL = Duration.ofMinutes(10);

    /** Result of entering a room. */
    public record Joined(Room room, RoomPlayer player, boolean reconnected) {
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Outbound outbound;
    private final Clock clock;
    private final Random random;

    @Autowired
    public RoomService(Outbound outbound) {
        this(outbound, Clock.systemUTC(), new SecureRandom());
    }

    RoomService(Outbound outbound, Clock clock, Random random) {
        this.outbound = outbound;
        this.clock = clock;
        this.random = random;
    }

    // ---------------------------------------------------------------- entering and leaving

    public Joined create(String nickname, String sessionId) {
        String name = cleanNickname(nickname);
        Room room = new Room(newCode(), clock.instant());
        rooms.put(room.code(), room);
        synchronized (room) {
            RoomPlayer host = addPlayer(room, name, sessionId);
            room.setHostId(host.id());
            outbound.attach(sessionId, room.code(), host.id());
            welcome(room, host);
            system(room, host.nickname() + " created the room");
            broadcast(room, List.of());
            log.info("Room {} created by {}", room.code(), host.nickname());
            return new Joined(room, host, false);
        }
    }

    public Joined join(String code, String nickname, String token, String sessionId) {
        Room room = requireRoom(code);
        synchronized (room) {
            RoomPlayer existing = room.playerByToken(token).orElse(null);
            if (existing != null) {
                boolean wasOffline = !existing.connected();
                existing.connect(sessionId);
                outbound.attach(sessionId, room.code(), existing.id());
                room.touch(clock.instant());
                List<GameEvent> events = new ArrayList<>();
                if (room.game() != null) {
                    room.game().setConnected(existing.id(), true);
                    events.addAll(room.game().drainEvents());
                }
                welcome(room, existing);
                if (wasOffline) {
                    system(room, existing.nickname() + " is back");
                }
                broadcast(room, events);
                return new Joined(room, existing, true);
            }

            String name = cleanNickname(nickname);
            boolean taken = room.players().stream().anyMatch(p -> p.nickname().equalsIgnoreCase(name));
            if (taken) {
                throw new GameException("Someone in this room is already called " + name);
            }
            RoomPlayer player = addPlayer(room, name, sessionId);
            outbound.attach(sessionId, room.code(), player.id());
            welcome(room, player);
            system(room, player.nickname() + " joined" + (room.game() != null ? " and will play the next game" : ""));
            broadcast(room, List.of());
            return new Joined(room, player, false);
        }
    }

    /** The transport dropped; keep the seat so the player can come back with their token. */
    public void disconnect(String code, String playerId, String sessionId) {
        Room room = rooms.get(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            RoomPlayer player = room.player(playerId).orElse(null);
            if (player == null || !sessionId.equals(player.sessionId())) {
                return; // a newer session already replaced this one
            }
            player.disconnect(clock.instant());
            List<GameEvent> events = new ArrayList<>();
            if (room.game() != null) {
                room.game().setConnected(playerId, false);
                events.addAll(room.game().drainEvents());
            }
            system(room, player.nickname() + " disconnected");
            broadcast(room, events);
        }
    }

    public void leave(String code, String playerId) {
        Room room = rooms.get(code);
        if (room == null) {
            return;
        }
        synchronized (room) {
            room.player(playerId).ifPresent(player -> removePlayer(room, player, player.nickname() + " left"));
        }
    }

    public void kick(String code, String hostId, String targetId) {
        Room room = requireRoom(code);
        synchronized (room) {
            requireHost(room, hostId);
            RoomPlayer target = room.player(targetId).orElseThrow(() -> new GameException("That player is not here"));
            if (target.id().equals(hostId)) {
                throw new GameException("You cannot kick yourself");
            }
            outbound.send(target.id(), Map.of("type", "kicked", "message", "The host removed you from the room"));
            removePlayer(room, target, target.nickname() + " was removed by the host");
        }
    }

    // ---------------------------------------------------------------- lobby

    public void updateSettings(String code, String playerId, GameSettings settings) {
        Room room = requireRoom(code);
        synchronized (room) {
            requireHost(room, playerId);
            if (room.game() != null) {
                throw new GameException("Settings can only be changed between games");
            }
            room.setSettings(settings);
            room.touch(clock.instant());
            broadcast(room, List.of(new GameEvent("settingsChanged")));
        }
    }

    public void startGame(String code, String playerId) {
        Room room = requireRoom(code);
        synchronized (room) {
            requireHost(room, playerId);
            if (room.game() != null && room.game().phase() == BluffGame.Phase.PLAYING) {
                throw new GameException("A game is already running");
            }
            List<GameEvent> events = new ArrayList<>();
            if (room.game() != null) {
                events.add(new GameEvent("restart"));
            }
            startNewGame(room, events);
        }
    }

    /** Host ends the current game (or clears a finished one) and everyone returns to the lobby. */
    public void endGame(String code, String playerId) {
        Room room = requireRoom(code);
        synchronized (room) {
            requireHost(room, playerId);
            if (room.game() == null) {
                throw new GameException("There is no game to end");
            }
            room.setGame(null);
            room.touch(clock.instant());
            system(room, "The host ended the game. Back to the lobby.");
            broadcast(room, List.of(new GameEvent("gameEnded")));
        }
    }

    // ---------------------------------------------------------------- in-game actions

    public void play(String code, String playerId, List<Integer> cardIds, Rank rank) {
        inGame(code, playerId, game -> game.play(playerId, cardIds, rank));
    }

    public void pass(String code, String playerId) {
        inGame(code, playerId, game -> game.pass(playerId));
    }

    public void callBluff(String code, String playerId) {
        inGame(code, playerId, game -> game.callBluff(playerId));
    }

    public void vote(String code, String playerId, boolean yes) {
        Room room = requireRoom(code);
        synchronized (room) {
            BluffGame game = requireGame(room, playerId);
            boolean unanimous = game.vote(playerId, yes);
            room.touch(clock.instant());
            List<GameEvent> events = new ArrayList<>(game.drainEvents());
            if (unanimous) {
                system(room, "Everyone voted to restart. New game!");
                events.add(new GameEvent("restart"));
                startNewGame(room, events);
            } else {
                broadcast(room, events);
            }
        }
    }

    public void chat(String code, String playerId, String text) {
        Room room = requireRoom(code);
        synchronized (room) {
            RoomPlayer player = room.player(playerId).orElseThrow(() -> new GameException("You are not in this room"));
            String clean = text == null ? "" : text.strip();
            if (clean.isEmpty()) {
                return;
            }
            if (clean.length() > MAX_CHAT) {
                clean = clean.substring(0, MAX_CHAT);
            }
            room.touch(clock.instant());
            ChatMessage message = new ChatMessage(player.id(), player.nickname(), clean, clock.millis(), false);
            room.addChat(message);
            broadcastChat(room, message);
        }
    }

    // ---------------------------------------------------------------- housekeeping

    /** Passes (or skips) players who let their turn timer run down. */
    @Scheduled(fixedDelay = 1_000)
    public void tickTurnTimers() {
        for (Room room : List.copyOf(rooms.values())) {
            synchronized (room) {
                BluffGame game = room.game();
                if (game != null && game.phase() == BluffGame.Phase.PLAYING && game.expireTurn()) {
                    room.touch(clock.instant());
                    broadcast(room, game.drainEvents());
                }
            }
        }
    }

    /** Drops players who never came back and rooms nobody uses any more. */
    @Scheduled(fixedDelay = 15_000)
    public void cleanup() {
        Instant now = clock.instant();
        for (Room room : List.copyOf(rooms.values())) {
            synchronized (room) {
                for (RoomPlayer player : List.copyOf(room.players())) {
                    boolean idleInLobby = room.phase() != RoomPhase.PLAYING
                            && !player.connected()
                            && player.disconnectedAt() != null
                            && Duration.between(player.disconnectedAt(), now).compareTo(LOBBY_DISCONNECT_GRACE) >= 0;
                    if (idleInLobby) {
                        removePlayer(room, player, player.nickname() + " timed out");
                    }
                }
                boolean abandoned = room.players().isEmpty()
                        || (room.connectedCount() == 0
                            && Duration.between(room.lastActivity(), now).compareTo(EMPTY_ROOM_TTL) >= 0);
                if (abandoned) {
                    rooms.remove(room.code());
                    log.info("Room {} removed", room.code());
                }
            }
        }
    }

    public Room room(String code) {
        return rooms.get(code == null ? "" : code.toUpperCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- internals

    private void inGame(String code, String playerId, Consumer<BluffGame> action) {
        Room room = requireRoom(code);
        synchronized (room) {
            BluffGame game = requireGame(room, playerId);
            action.accept(game);
            room.touch(clock.instant());
            broadcast(room, game.drainEvents());
        }
    }

    private void startNewGame(Room room, List<GameEvent> leadingEvents) {
        List<String> seated = room.players().stream()
                .filter(RoomPlayer::connected)
                .map(RoomPlayer::id)
                .toList();
        if (seated.size() < 2) {
            throw new GameException("At least two connected players are needed");
        }
        GameSettings rules = room.settings().effective(seated.size());
        BluffGame game = BluffGame.start(rules, seated, random, clock::millis);
        room.setGame(game);
        room.touch(clock.instant());
        List<GameEvent> events = new ArrayList<>(leadingEvents);
        events.addAll(game.drainEvents());
        system(room, "Game started with " + seated.size() + " players: "
                + rules.decks() + " deck(s), " + rules.cardsPerPlayer() + " cards each"
                + (rules.splitEqually() ? " (pile split equally)" : "")
                + (rules.jokers() ? ", jokers in" : ", no jokers"));
        broadcast(room, events);
    }

    private RoomPlayer addPlayer(Room room, String nickname, String sessionId) {
        RoomPlayer player = new RoomPlayer(newId(), UUID.randomUUID().toString(), nickname, sessionId);
        room.mutablePlayers().add(player);
        room.touch(clock.instant());
        return player;
    }

    private void removePlayer(Room room, RoomPlayer player, String announcement) {
        room.mutablePlayers().remove(player);
        room.touch(clock.instant());
        List<GameEvent> events = new ArrayList<>();
        if (room.game() != null) {
            room.game().removePlayer(player.id());
            events.addAll(room.game().drainEvents());
        }
        if (room.players().isEmpty()) {
            rooms.remove(room.code());
            log.info("Room {} is empty and was removed", room.code());
            return;
        }
        if (room.isHost(player.id())) {
            RoomPlayer newHost = room.players().stream().filter(RoomPlayer::connected).findFirst()
                    .orElse(room.players().get(0));
            room.setHostId(newHost.id());
            events.add(new GameEvent("hostChanged").with("playerId", newHost.id()));
            announcement += ". " + newHost.nickname() + " is now the host";
        }
        system(room, announcement);
        broadcast(room, events);
    }

    private void welcome(Room room, RoomPlayer player) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "welcome");
        message.put("playerId", player.id());
        message.put("token", player.token());
        message.put("roomCode", room.code());
        message.put("nickname", player.nickname());
        outbound.send(player.id(), message);
        outbound.send(player.id(), Map.of("type", "chatHistory", "messages", room.chatHistory()));
    }

    private void system(Room room, String text) {
        ChatMessage message = ChatMessage.system(text, clock.millis());
        room.addChat(message);
        broadcastChat(room, message);
    }

    private void broadcastChat(Room room, ChatMessage message) {
        Map<String, Object> payload = Map.of("type", "chat", "message", message);
        for (RoomPlayer player : room.players()) {
            if (player.connected()) {
                outbound.send(player.id(), payload);
            }
        }
    }

    private void broadcast(Room room, List<GameEvent> events) {
        long now = clock.millis();
        for (RoomPlayer player : room.players()) {
            if (!player.connected()) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "update");
            payload.put("events", events);
            payload.put("state", StateView.build(room, player, now));
            outbound.send(player.id(), payload);
        }
    }

    private Room requireRoom(String code) {
        Room room = room(code);
        if (room == null) {
            throw new GameException("Room " + (code == null ? "" : code.toUpperCase(Locale.ROOT)) + " does not exist");
        }
        return room;
    }

    private BluffGame requireGame(Room room, String playerId) {
        if (room.game() == null) {
            throw new GameException("The game has not started");
        }
        if (!room.isSeated(playerId)) {
            throw new GameException("You are watching this game; you will play in the next one");
        }
        return room.game();
    }

    private void requireHost(Room room, String playerId) {
        if (!room.isHost(playerId)) {
            throw new GameException("Only the host can do that");
        }
    }

    private String cleanNickname(String nickname) {
        String name = nickname == null ? "" : nickname.strip().replaceAll("[\\p{Cntrl}]", "");
        if (name.isEmpty()) {
            throw new GameException("Pick a nickname first");
        }
        if (name.length() > MAX_NICKNAME) {
            name = name.substring(0, MAX_NICKNAME);
        }
        return name;
    }

    private String newCode() {
        while (true) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            if (!rooms.containsKey(code.toString())) {
                return code.toString();
            }
        }
    }

    private String newId() {
        byte[] bytes = new byte[5];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
