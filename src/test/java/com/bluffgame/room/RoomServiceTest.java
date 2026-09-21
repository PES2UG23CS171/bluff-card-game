package com.bluffgame.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bluffgame.engine.GameException;
import com.bluffgame.model.GameSettings;
import com.bluffgame.model.Rank;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoomServiceTest {

    /** Records everything sent to every player. */
    static final class FakeOutbound implements Outbound {
        final Map<String, List<Map<String, Object>>> byPlayer = new ConcurrentHashMap<>();
        final Map<String, String> attached = new ConcurrentHashMap<>();

        @Override
        public void attach(String sessionId, String roomCode, String playerId) {
            attached.put(sessionId, playerId);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(String playerId, Object message) {
            byPlayer.computeIfAbsent(playerId, k -> new ArrayList<>()).add((Map<String, Object>) message);
        }

        List<Map<String, Object>> of(String playerId, String type) {
            return byPlayer.getOrDefault(playerId, List.of()).stream().filter(m -> type.equals(m.get("type"))).toList();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> lastState(String playerId) {
            List<Map<String, Object>> updates = of(playerId, "update");
            return (Map<String, Object>) updates.get(updates.size() - 1).get("state");
        }
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-21T12:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final FakeOutbound outbound = new FakeOutbound();
    private final MutableClock clock = new MutableClock();
    private RoomService service;

    @BeforeEach
    void setUp() {
        service = new RoomService(outbound, clock, new Random(7));
    }

    @Test
    void creatingARoomMakesTheCreatorHostAndWelcomesThem() {
        RoomService.Joined joined = service.create("  Alice ", "s1");

        assertThat(joined.room().code()).hasSize(6);
        assertThat(joined.room().isHost(joined.player().id())).isTrue();
        assertThat(joined.player().nickname()).isEqualTo("Alice");
        assertThat(outbound.attached).containsEntry("s1", joined.player().id());
        assertThat(outbound.of(joined.player().id(), "welcome")).hasSize(1);
        assertThat(outbound.of(joined.player().id(), "welcome").get(0)).containsEntry("token", joined.player().token());
        Map<String, Object> state = outbound.lastState(joined.player().id());
        assertThat(((Map<?, ?>) state.get("room")).get("phase")).isEqualTo("LOBBY");
        assertThat(state.get("game")).isNull();
    }

    @Test
    void joiningNeedsAKnownRoomAndAFreeNickname() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();

        assertThatThrownBy(() -> service.join("NOPE12", "Bob", null, "s2")).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> service.join(code, "alice", null, "s2")).hasMessageContaining("already called");
        assertThatThrownBy(() -> service.join(code, "   ", null, "s2")).hasMessageContaining("nickname");

        RoomService.Joined bob = service.join(code.toLowerCase(), "Bob", null, "s2");
        assertThat(bob.reconnected()).isFalse();
        assertThat(host.room().players()).hasSize(2);
        assertThat(outbound.lastState(host.player().id()).get("players")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(2);
    }

    @Test
    void aTokenReconnectsTheSamePlayer() {
        RoomService.Joined host = service.create("Alice", "s1");
        RoomService.Joined bob = service.join(host.room().code(), "Bob", null, "s2");
        service.disconnect(host.room().code(), bob.player().id(), "s2");
        assertThat(bob.player().connected()).isFalse();

        RoomService.Joined back = service.join(host.room().code(), "ignored", bob.player().token(), "s3");

        assertThat(back.reconnected()).isTrue();
        assertThat(back.player()).isSameAs(bob.player());
        assertThat(bob.player().connected()).isTrue();
        assertThat(outbound.attached).containsEntry("s3", bob.player().id());
    }

    @Test
    void aStaleDisconnectFromAReplacedSessionIsIgnored() {
        RoomService.Joined host = service.create("Alice", "s1");
        service.join(host.room().code(), "ignored", host.player().token(), "s9");

        service.disconnect(host.room().code(), host.player().id(), "s1");

        assertThat(host.player().connected()).isTrue();
    }

    @Test
    void onlyTheHostChangesSettingsAndStartsAndNeedsTwoPlayers() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();
        GameSettings settings = new GameSettings(2, 12, false, 3, 0);

        assertThatThrownBy(() -> service.startGame(code, host.player().id())).hasMessageContaining("two connected players");
        RoomService.Joined bob = service.join(code, "Bob", null, "s2");
        assertThatThrownBy(() -> service.updateSettings(code, bob.player().id(), settings)).hasMessageContaining("Only the host");
        assertThatThrownBy(() -> service.startGame(code, bob.player().id())).hasMessageContaining("Only the host");

        service.updateSettings(code, host.player().id(), settings);
        assertThat(host.room().settings()).isEqualTo(settings);

        service.startGame(code, host.player().id());

        assertThat(host.room().phase()).isEqualTo(RoomPhase.PLAYING);
        Map<String, Object> aliceState = outbound.lastState(host.player().id());
        Map<String, Object> bobState = outbound.lastState(bob.player().id());
        assertThat(((List<?>) ((Map<?, ?>) aliceState.get("you")).get("hand"))).hasSize(12);
        assertThat(((List<?>) ((Map<?, ?>) bobState.get("you")).get("hand"))).hasSize(12);
        assertThat(((Map<?, ?>) aliceState.get("game")).get("round")).isEqualTo(1);
        List<?> players = (List<?>) aliceState.get("players");
        assertThat(players).allSatisfy(p -> assertThat(((Map<?, ?>) p).get("cards")).isEqualTo(12));
        assertThatThrownBy(() -> service.updateSettings(code, host.player().id(), settings)).hasMessageContaining("between games");
    }

    @Test
    void lateJoinersWatchUntilTheNextGame() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();
        service.join(code, "Bob", null, "s2");
        service.startGame(code, host.player().id());

        RoomService.Joined carol = service.join(code, "Carol", null, "s3");

        Map<String, Object> state = outbound.lastState(carol.player().id());
        assertThat(((Map<?, ?>) state.get("you")).get("seated")).isEqualTo(false);
        assertThatThrownBy(() -> service.pass(code, carol.player().id())).hasMessageContaining("watching");
    }

    @Test
    void chatIsBroadcastAndReplayedToNewcomers() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();
        service.chat(code, host.player().id(), "  hello there  ");
        service.chat(code, host.player().id(), "   ");

        RoomService.Joined bob = service.join(code, "Bob", null, "s2");

        List<Map<String, Object>> aliceChat = outbound.of(host.player().id(), "chat");
        ChatMessage said = (ChatMessage) aliceChat.stream()
                .map(m -> (ChatMessage) m.get("message"))
                .filter(m -> !m.system())
                .findFirst().orElseThrow();
        assertThat(said.text()).isEqualTo("hello there");
        assertThat(said.nickname()).isEqualTo("Alice");
        List<?> history = (List<?>) outbound.of(bob.player().id(), "chatHistory").get(0).get("messages");
        assertThat(history).anySatisfy(m -> assertThat(((ChatMessage) m).text()).isEqualTo("hello there"));
    }

    @Test
    void leavingHandsHostToTheNextPlayerAndEmptyRoomsVanish() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();
        RoomService.Joined bob = service.join(code, "Bob", null, "s2");

        service.leave(code, host.player().id());

        assertThat(host.room().isHost(bob.player().id())).isTrue();
        assertThat(service.room(code)).isSameAs(host.room());

        service.leave(code, bob.player().id());
        assertThat(service.room(code)).isNull();
    }

    @Test
    void inGameActionsFlowThroughToTheEngineAndErrorsSurface() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();
        RoomService.Joined bob = service.join(code, "Bob", null, "s2");
        service.updateSettings(code, host.player().id(), new GameSettings(1, 5, false, 0, 0));
        service.startGame(code, host.player().id());

        String onTurn = (String) ((Map<?, ?>) outbound.lastState(host.player().id()).get("game")).get("turnPlayerId");
        String other = onTurn.equals(host.player().id()) ? bob.player().id() : host.player().id();
        List<?> hand = (List<?>) ((Map<?, ?>) outbound.lastState(onTurn).get("you")).get("hand");
        com.bluffgame.model.Card first = (com.bluffgame.model.Card) hand.get(0);

        assertThatThrownBy(() -> service.play(code, other, List.of(first.id()), first.rank()))
                .isInstanceOf(GameException.class).hasMessageContaining("not your turn");
        service.play(code, onTurn, List.of(first.id()), first.rank());

        Map<String, Object> game = (Map<String, Object>) outbound.lastState(other).get("game");
        assertThat(game.get("potCount")).isEqualTo(1);
        assertThat(game.get("turnPlayerId")).isEqualTo(other);
        assertThat(game.get("challengeOpen")).isEqualTo(true);

        service.callBluff(code, other); // honest, so the caller takes the card
        Map<String, Object> after = (Map<String, Object>) outbound.lastState(other).get("game");
        assertThat(after.get("potCount")).isEqualTo(0);
        assertThat(after.get("turnPlayerId")).isEqualTo(onTurn);
    }

    @Test
    void theTurnTimerPassesPlayersWhoWalkAway() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();
        RoomService.Joined bob = service.join(code, "Bob", null, "s2");
        service.updateSettings(code, host.player().id(), new GameSettings(1, 5, false, 0, 10));
        service.startGame(code, host.player().id());
        String opener = host.room().game().turnPlayerId();

        service.tickTurnTimers();
        assertThat(host.room().game().turnPlayerId()).isEqualTo(opener);

        clock.now = clock.now.plusSeconds(11);
        service.tickTurnTimers();

        assertThat(host.room().game().turnPlayerId()).isNotEqualTo(opener);
        List<?> events = (List<?>) outbound.of(bob.player().id(), "update").getLast().get("events");
        assertThat(events.toString()).contains("turnTimedOut");
    }

    @Test
    void cleanupDropsPlayersWhoNeverCameBack() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();
        RoomService.Joined bob = service.join(code, "Bob", null, "s2");
        service.disconnect(code, bob.player().id(), "s2");

        service.cleanup();
        assertThat(host.room().players()).hasSize(2);

        clock.now = clock.now.plus(RoomService.LOBBY_DISCONNECT_GRACE).plus(Duration.ofSeconds(1));
        service.cleanup();
        assertThat(host.room().players()).hasSize(1);

        service.disconnect(code, host.player().id(), "s1");
        clock.now = clock.now.plus(RoomService.EMPTY_ROOM_TTL).plus(Duration.ofSeconds(1));
        service.cleanup();
        assertThat(service.room(code)).isNull();
    }

    @Test
    void unanimousRestartVoteDealsANewGame() {
        RoomService.Joined host = service.create("Alice", "s1");
        String code = host.room().code();
        RoomService.Joined bob = service.join(code, "Bob", null, "s2");
        service.updateSettings(code, host.player().id(), new GameSettings(1, 1, false, 0, 0));
        service.startGame(code, host.player().id());
        String onTurn = (String) ((Map<?, ?>) outbound.lastState(host.player().id()).get("game")).get("turnPlayerId");
        String other = onTurn.equals(host.player().id()) ? bob.player().id() : host.player().id();
        com.bluffgame.model.Card only = (com.bluffgame.model.Card) ((List<?>) ((Map<?, ?>) outbound.lastState(onTurn).get("you")).get("hand")).get(0);
        service.play(code, onTurn, List.of(only.id()), Rank.ACE);
        service.play(code, other, List.of(((com.bluffgame.model.Card) ((List<?>) ((Map<?, ?>) outbound.lastState(other).get("you")).get("hand")).get(0)).id()), Rank.ACE);
        assertThat(host.room().phase()).isEqualTo(RoomPhase.GAME_OVER);

        service.vote(code, host.player().id(), true);
        assertThat(host.room().phase()).isEqualTo(RoomPhase.GAME_OVER);
        service.vote(code, bob.player().id(), true);

        assertThat(host.room().phase()).isEqualTo(RoomPhase.PLAYING);
        assertThat(host.room().game().round()).isEqualTo(1);
        List<?> events = (List<?>) outbound.of(bob.player().id(), "update").getLast().get("events");
        assertThat(events.toString()).contains("restart").contains("deal");
    }
}
