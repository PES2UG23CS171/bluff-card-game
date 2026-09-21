package com.bluffgame.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeckFactoryTest {

    @Test
    void singleDeckWithoutJokersHasFiftyTwoUniqueCards() {
        List<Card> cards = DeckFactory.build(1, false);

        assertThat(cards).hasSize(52);
        assertThat(cards.stream().map(Card::id).distinct()).hasSize(52);
        assertThat(cards.stream().map(c -> c.rank() + "/" + c.suit()).distinct()).hasSize(52);
        assertThat(cards).noneMatch(Card::isJoker);
    }

    @Test
    void jokersAddTwoWildCardsPerDeck() {
        List<Card> cards = DeckFactory.build(3, true);

        assertThat(cards).hasSize(3 * 54);
        assertThat(cards.stream().filter(Card::isJoker)).hasSize(6);
        assertThat(cards.stream().map(Card::id).distinct()).hasSize(3 * 54);
        assertThat(DeckFactory.size(3, true)).isEqualTo(162);
    }

    @Test
    void multipleDecksRepeatEveryRankAndSuit() {
        List<Card> cards = DeckFactory.build(2, false);

        long kingsOfHearts = cards.stream()
                .filter(c -> c.rank() == Rank.KING && c.suit() == Suit.HEARTS)
                .count();
        assertThat(kingsOfHearts).isEqualTo(2);
    }

    @Test
    void jokerMatchesAnyDeclaredRankButRegularCardsOnlyTheirOwn() {
        Card joker = Card.joker(99);
        Card king = new Card(1, Rank.KING, Suit.CLUBS);

        assertThat(joker.matches(Rank.ACE)).isTrue();
        assertThat(joker.matches(Rank.KING)).isTrue();
        assertThat(king.matches(Rank.KING)).isTrue();
        assertThat(king.matches(Rank.QUEEN)).isFalse();
    }

    @Test
    void rankLabelsRoundTripAndAcceptOneForAce() {
        assertThat(Rank.fromLabel("K")).isEqualTo(Rank.KING);
        assertThat(Rank.fromLabel("10")).isEqualTo(Rank.TEN);
        assertThat(Rank.fromLabel("a")).isEqualTo(Rank.ACE);
        assertThat(Rank.fromLabel("1")).isEqualTo(Rank.ACE);
        assertThat(Rank.declarable()).hasSize(13).doesNotContain(Rank.JOKER);
        assertThatThrownBy(() -> Rank.fromLabel("X")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void displayOrderSortsByRankThenSuitWithJokersLast() {
        Card joker = Card.joker(0);
        Card aceSpades = new Card(1, Rank.ACE, Suit.SPADES);
        Card aceHearts = new Card(2, Rank.ACE, Suit.HEARTS);
        Card king = new Card(3, Rank.KING, Suit.CLUBS);

        List<Card> sorted = new java.util.ArrayList<>(List.of(king, joker, aceHearts, aceSpades));
        sorted.sort(Card.DISPLAY_ORDER);

        assertThat(sorted).containsExactly(aceSpades, aceHearts, king, joker);
    }
}
