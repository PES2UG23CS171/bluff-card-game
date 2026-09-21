package com.bluffgame.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GameSettingsTest {

    @Test
    void defaultsAreValid() {
        GameSettings settings = GameSettings.defaults();

        assertThat(settings.decks()).isEqualTo(1);
        assertThat(settings.jokers()).isTrue();
        assertThat(settings.totalCards()).isEqualTo(54);
        assertThat(settings.rankMode()).isEqualTo(RankMode.ROUND);
    }

    @Test
    void rejectsImpossibleValues() {
        assertThatThrownBy(() -> new GameSettings(0, 5, false, RankMode.ROUND, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 0, false, RankMode.ROUND, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 53, false, RankMode.ROUND, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 5, false, RankMode.ROUND, 31))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 5, false, null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dealFeasibilityDependsOnPlayerCount() {
        GameSettings settings = new GameSettings(2, 20, true, RankMode.FREE, 0);

        assertThat(settings.totalCards()).isEqualTo(108);
        assertThat(settings.maxCardsPerPlayer(5)).isEqualTo(21);
        assertThat(settings.canDeal(5)).isTrue();
        assertThat(settings.canDeal(6)).isFalse();
    }
}
