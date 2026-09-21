package com.bluffgame.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bluffgame.model.Card;
import com.bluffgame.model.GameSettings;
import com.bluffgame.model.Rank;
import com.bluffgame.model.Suit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BluffGameTest {

    private static final Card K_SPADES = new Card(1, Rank.KING, Suit.SPADES);
    private static final Card K_HEARTS = new Card(2, Rank.KING, Suit.HEARTS);
    private static final Card Q_CLUBS = new Card(3, Rank.QUEEN, Suit.CLUBS);
    private static final Card A_SPADES = new Card(4, Rank.ACE, Suit.SPADES);
    private static final Card FIVE_DIAMONDS = new Card(5, Rank.FIVE, Suit.DIAMONDS);
    private static final Card JOKER = Card.joker(6);
    private static final Card NINE_CLUBS = new Card(7, Rank.NINE, Suit.CLUBS);
    private static final Card K_DIAMONDS = new Card(8, Rank.KING, Suit.DIAMONDS);

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private BluffGame game;

    @BeforeEach
    void threePlayerGame() {
        game = threePlayers(0);
    }

    private BluffGame threePlayers(int callWindowSeconds) {
        Map<String, List<Card>> hands = new LinkedHashMap<>();
        hands.put("A", List.of(K_SPADES, K_HEARTS, Q_CLUBS));
        hands.put("B", List.of(A_SPADES, FIVE_DIAMONDS, JOKER));
        hands.put("C", List.of(NINE_CLUBS, K_DIAMONDS));
        return BluffGame.startWithHands(new GameSettings(1, 3, true, callWindowSeconds), hands, "A", clock::get);
    }

    private static List<String> kinds(List<GameEvent> events) {
        return events.stream().map(GameEvent::kind).toList();
    }

    // ------------------------------------------------------------ dealing

    @Test
    void startDealsTheConfiguredNumberOfCardsToEveryPlayer() {
        GameSettings settings = new GameSettings(2, 10, true, 5);
        BluffGame dealt = BluffGame.start(settings, List.of("p1", "p2", "p3", "p4"), new Random(42), clock::get);

        assertThat(dealt.seats()).hasSize(4);
        assertThat(dealt.seats()).allSatisfy(seat -> assertThat(seat.cardCount()).isEqualTo(10));
        long distinctIds = dealt.seats().stream().flatMap(seat -> seat.hand().stream()).map(Card::id).distinct().count();
        assertThat(distinctIds).isEqualTo(40);
        assertThat(dealt.turnPlayerId()).isIn("p1", "p2", "p3", "p4");
        assertThat(dealt.round()).isEqualTo(1);
        assertThat(dealt.mustPlay()).isTrue();
        assertThat(kinds(dealt.drainEvents())).containsExactly("deal", "roundStart", "turn");
    }

    @Test
    void startRejectsTooFewPlayersOrTooManyCards() {
        GameSettings settings = new GameSettings(1, 20, false, 5);

        assertThatThrownBy(() -> BluffGame.start(settings, List.of("solo"), new Random(), clock::get))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("two players");
        assertThatThrownBy(() -> BluffGame.start(settings, List.of("a", "b", "c"), new Random(), clock::get))
                .isInstanceOf(GameException.class)
                .hasMessageContaining("Not enough cards");
    }

    // ------------------------------------------------------------ playing

    @Test
    void playingMovesCardsToThePotAndPassesTheTurn() {
        game.drainEvents();

        game.play("A", List.of(1, 2), Rank.KING);

        assertThat(game.seat("A").cardCount()).isEqualTo(1);
        assertThat(game.potCardCount()).isEqualTo(2);
        assertThat(game.currentRank()).isEqualTo(Rank.KING);
        assertThat(game.turnPlayerId()).isEqualTo("B");
        assertThat(game.challengeOpen()).isTrue();
        assertThat(game.lastPlay().playerId()).isEqualTo("A");
        List<GameEvent> events = game.drainEvents();
        assertThat(kinds(events)).containsExactly("play", "turn");
        assertThat(events.get(0).get("count")).isEqualTo(2);
        assertThat(events.get(0).get("rank")).isEqualTo(Rank.KING);
    }

    @Test
    void playValidatesTurnCardsAndRank() {
        assertThatThrownBy(() -> game.play("B", List.of(4), Rank.ACE)).hasMessageContaining("not your turn");
        assertThatThrownBy(() -> game.play("A", List.of(), Rank.KING)).hasMessageContaining("at least one card");
        assertThatThrownBy(() -> game.play("A", List.of(4), Rank.KING)).hasMessageContaining("do not hold");
        assertThatThrownBy(() -> game.play("A", List.of(1, 1), Rank.KING)).hasMessageContaining("selected twice");
        assertThatThrownBy(() -> game.play("A", List.of(1), Rank.JOKER)).hasMessageContaining("Pick the rank");
        assertThatThrownBy(() -> game.pass("A")).hasMessageContaining("have to play");
    }

    @Test
    void whoeverOpensTheRoundFixesTheRankForEveryoneElse() {
        assertThat(game.currentRank()).isNull();
        game.play("A", List.of(1), Rank.KING);
        assertThat(game.currentRank()).isEqualTo(Rank.KING);
        assertThatThrownBy(() -> game.play("B", List.of(4), Rank.ACE)).hasMessageContaining("played as Ks");
        game.play("B", List.of(4), Rank.KING);
        assertThat(game.potCardCount()).isEqualTo(2);
        assertThat(game.currentRank()).isEqualTo(Rank.KING);
    }

    // ------------------------------------------------------------ passing

    @Test
    void whenEveryoneElsePassesThePotIsSetAsideAndTheLastPlayerOpensTheNextRound() {
        game.play("A", List.of(3), Rank.QUEEN);
        game.play("B", List.of(5), Rank.QUEEN);
        game.drainEvents();

        game.pass("C");
        assertThat(game.turnPlayerId()).isEqualTo("A");
        game.pass("A");

        assertThat(game.potCardCount()).isZero();
        assertThat(game.setAsideCards()).isEqualTo(2);
        assertThat(game.round()).isEqualTo(2);
        assertThat(game.turnPlayerId()).isEqualTo("B");
        assertThat(game.currentRank()).isNull();
        assertThat(game.mustPlay()).isTrue();
        List<GameEvent> events = game.drainEvents();
        assertThat(kinds(events)).containsExactly("pass", "turn", "pass", "potSetAside", "roundStart", "turn");
        GameEvent roundStart = events.get(4);
        assertThat(roundStart.get("starterId")).isEqualTo("B");
        assertThat(roundStart.get("reason")).isEqualTo("allPassed");
    }

    // ------------------------------------------------------------ calling bluff

    @Test
    void anHonestPlayHandsThePotToTheCallerAndTheHonestPlayerStartsTheNextRound() {
        game.play("A", List.of(1, 2), Rank.KING);
        game.drainEvents();

        game.callBluff("C");

        assertThat(game.seat("C").cardCount()).isEqualTo(4);
        assertThat(game.seat("A").cardCount()).isEqualTo(1);
        assertThat(game.potCardCount()).isZero();
        assertThat(game.turnPlayerId()).isEqualTo("A");
        assertThat(game.round()).isEqualTo(2);
        List<GameEvent> events = game.drainEvents();
        assertThat(kinds(events)).containsExactly("bluffCalled", "roundStart", "turn");
        GameEvent called = events.get(0);
        assertThat(called.get("honest")).isEqualTo(true);
        assertThat(called.get("receiverId")).isEqualTo("C");
        assertThat(called.get("cards")).isEqualTo(List.of(K_SPADES, K_HEARTS));
        assertThat(events.get(1).get("reason")).isEqualTo("honest");
    }

    @Test
    void aCaughtBluffHandsTheWholePotToTheBlufferAndTheCallerStartsTheNextRound() {
        game.play("A", List.of(1), Rank.KING);
        game.play("B", List.of(5), Rank.KING); // a five claimed as a king
        game.drainEvents();

        game.callBluff("C");

        assertThat(game.seat("B").cardCount()).isEqualTo(4);
        assertThat(game.seat("C").cardCount()).isEqualTo(2);
        assertThat(game.turnPlayerId()).isEqualTo("C");
        List<GameEvent> events = game.drainEvents();
        assertThat(events.get(0).get("honest")).isEqualTo(false);
        assertThat(events.get(0).get("receiverId")).isEqualTo("B");
        assertThat(events.get(0).get("potCount")).isEqualTo(2);
        assertThat(events.get(1).get("reason")).isEqualTo("caught");
    }

    @Test
    void jokersCountAsTheDeclaredRank() {
        game.play("A", List.of(1), Rank.KING);
        game.play("B", List.of(6), Rank.KING); // the joker
        game.drainEvents();

        game.callBluff("C");

        assertThat(game.drainEvents().get(0).get("honest")).isEqualTo(true);
        assertThat(game.seat("C").cardCount()).isEqualTo(4);
        assertThat(game.turnPlayerId()).isEqualTo("B");
    }

    @Test
    void aPlayCanOnlyBeCalledUntilTheNextPlayerActsAndNotByItsOwner() {
        assertThatThrownBy(() -> game.callBluff("B")).hasMessageContaining("no play to call");
        game.play("A", List.of(1), Rank.KING);
        assertThatThrownBy(() -> game.callBluff("A")).hasMessageContaining("your own play");

        game.pass("B");
        assertThat(game.challengeOpen()).isFalse();
        assertThatThrownBy(() -> game.callBluff("C")).hasMessageContaining("no play to call");
    }

    @Test
    void theNextPlayerHasToWaitForTheCallWindow() {
        BluffGame timed = threePlayers(5);
        timed.play("A", List.of(1), Rank.KING);

        assertThat(timed.callWindowEndsAt()).isEqualTo(1_000_000L + 5_000L);
        assertThatThrownBy(() -> timed.pass("B")).hasMessageContaining("Wait");
        assertThatThrownBy(() -> timed.play("B", List.of(4), Rank.KING)).hasMessageContaining("Wait");
        timed.callBluff("C"); // calling is always allowed
        assertThat(timed.seat("C").cardCount()).isEqualTo(3);

        clock.set(2_000_000L);
        timed.play("A", List.of(2), Rank.KING);
        clock.addAndGet(5_000L);
        timed.pass("B");
        assertThat(timed.turnPlayerId()).isEqualTo("C");
    }

    @Test
    void noWaitIsNeededWhenOnlyTheNextPlayerCouldCall() {
        Map<String, List<Card>> hands = new LinkedHashMap<>();
        hands.put("A", List.of(K_SPADES, K_HEARTS));
        hands.put("B", List.of(A_SPADES, FIVE_DIAMONDS));
        BluffGame duel = BluffGame.startWithHands(new GameSettings(1, 2, false, 10), hands, "A", clock::get);

        duel.play("A", List.of(1), Rank.KING);

        assertThat(duel.callWindowEndsAt()).isNull();
        duel.pass("B");
        assertThat(duel.turnPlayerId()).isEqualTo("A");
    }

    // ------------------------------------------------------------ finishing

    @Test
    void emptyingYourHandCountsOnceTheNextPlayerActs() {
        game.play("A", List.of(1, 2, 3), Rank.KING); // A bluffs with all cards
        assertThat(game.seat("A").finishPlace()).isNull();
        assertThat(game.voteOpen()).isFalse();
        game.drainEvents();

        game.pass("B");

        assertThat(game.seat("A").finishPlace()).isEqualTo(1);
        assertThat(game.finishOrder()).containsExactly("A");
        assertThat(game.voteOpen()).isTrue();
        assertThat(game.phase()).isEqualTo(BluffGame.Phase.PLAYING);
        assertThat(kinds(game.drainEvents())).containsExactly("playerFinished", "voteOpened", "pass", "turn");
        assertThat(game.turnPlayerId()).isEqualTo("C");
    }

    @Test
    void aCaughtBlufferWhoEmptiedTheirHandTakesThePotAndKeepsPlaying() {
        game.play("A", List.of(1, 2, 3), Rank.KING);

        game.callBluff("B");

        assertThat(game.seat("A").finishPlace()).isNull();
        assertThat(game.seat("A").cardCount()).isEqualTo(3);
        assertThat(game.turnPlayerId()).isEqualTo("B");
    }

    @Test
    void anHonestFinalPlayFinishesWhenCalledAndTheNextActivePlayerOpens() {
        game.play("A", List.of(3), Rank.QUEEN);
        game.play("B", List.of(4, 5, 6), Rank.QUEEN); // ace, five, joker as queens: a bluff
        game.callBluff("C");                             // B takes the pot (4 cards), C starts
        assertThat(game.turnPlayerId()).isEqualTo("C");
        game.play("C", List.of(7, 8), Rank.NINE);       // C's last cards, a bluff nobody calls

        game.play("A", List.of(1, 2), Rank.NINE);       // A acts, C is done

        assertThat(game.seat("C").finishPlace()).isEqualTo(1);
        game.drainEvents();
        game.callBluff("B");                             // A lied: A takes the pot, B opens
        assertThat(game.seat("A").cardCount()).isEqualTo(4);
        assertThat(game.turnPlayerId()).isEqualTo("B");

        game.play("B", List.of(3, 4, 5, 6), Rank.ACE);   // B empties their hand (a bluff)
        clock.addAndGet(1);
        game.callBluff("A");                             // caught: B takes 4 cards back
        assertThat(game.seat("B").cardCount()).isEqualTo(4);
        assertThat(game.turnPlayerId()).isEqualTo("A");
        assertThat(game.phase()).isEqualTo(BluffGame.Phase.PLAYING);
    }

    @Test
    void theLastPlayerHoldingCardsLosesAndTheGameEnds() {
        Map<String, List<Card>> hands = new LinkedHashMap<>();
        hands.put("A", List.of(K_SPADES));
        hands.put("B", List.of(A_SPADES, FIVE_DIAMONDS));
        BluffGame duel = BluffGame.startWithHands(new GameSettings(1, 2, false, 0), hands, "A", clock::get);
        duel.drainEvents();

        duel.play("A", List.of(1), Rank.KING);
        duel.callBluff("B"); // honest: B takes the card, A is finished, B is the only one left

        assertThat(duel.phase()).isEqualTo(BluffGame.Phase.GAME_OVER);
        assertThat(duel.loserId()).isEqualTo("B");
        assertThat(duel.finishOrder()).containsExactly("A");
        assertThat(duel.turnPlayerId()).isNull();
        List<GameEvent> events = duel.drainEvents();
        assertThat(kinds(events)).containsExactly("play", "turn", "bluffCalled", "playerFinished", "voteOpened", "gameOver");
        assertThat(events.get(5).get("standings")).isEqualTo(List.of("A", "B"));
        assertThatThrownBy(() -> duel.play("B", List.of(4), Rank.ACE)).hasMessageContaining("game is over");
    }

    @Test
    void restartNeedsEveryConnectedPlayerToVoteYes() {
        assertThatThrownBy(() -> game.vote("A", true)).hasMessageContaining("nothing to vote on");
        game.play("A", List.of(1, 2, 3), Rank.KING);
        game.pass("B"); // A is finished, voting opens

        assertThat(game.vote("A", true)).isFalse();
        assertThat(game.vote("B", false)).isFalse();
        assertThat(game.vote("C", true)).isFalse();
        assertThat(game.vote("B", true)).isTrue();
        assertThat(game.restartVotes()).containsEntry("B", true);

        game.setConnected("C", false);
        game.vote("C", false);
        assertThat(game.vote("A", true)).isTrue(); // offline players do not block a restart
    }

    // ------------------------------------------------------------ connectivity

    @Test
    void offlinePlayersAreSkippedAndPickUpWhenTheyReturn() {
        game.play("A", List.of(1), Rank.KING);
        game.setConnected("B", false);
        assertThat(game.turnPlayerId()).isEqualTo("C");

        game.pass("C");
        // B is offline, so everybody who could pass has passed: pot set aside, A opens again.
        assertThat(game.round()).isEqualTo(2);
        assertThat(game.turnPlayerId()).isEqualTo("A");
        assertThat(game.setAsideCards()).isEqualTo(1);

        game.setConnected("B", true);
        game.play("A", List.of(2), Rank.KING);
        assertThat(game.turnPlayerId()).isEqualTo("B");
    }

    @Test
    void whenThePlayerOnTurnGoesOfflineTheTurnMovesOn() {
        game.setConnected("A", false);
        assertThat(game.turnPlayerId()).isEqualTo("B");

        game.setConnected("B", false);
        game.setConnected("C", false);
        assertThat(game.turnPlayerId()).isNull();

        game.setConnected("C", true);
        assertThat(game.turnPlayerId()).isEqualTo("C");
    }

    @Test
    void aGameAbandonedByEveryoneElseEndsWithoutALoser() {
        game.removePlayer("B");
        assertThat(game.phase()).isEqualTo(BluffGame.Phase.PLAYING);
        assertThat(game.seat("B").left()).isTrue();

        game.removePlayer("C");

        assertThat(game.phase()).isEqualTo(BluffGame.Phase.GAME_OVER);
        assertThat(game.loserId()).isNull();
        assertThat(game.drainEvents().getLast().get("reason")).isEqualTo("abandoned");
    }
}
