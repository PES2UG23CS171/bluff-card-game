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
    }

    @Test
    void rejectsImpossibleValues() {
        assertThatThrownBy(() -> new GameSettings(0, 5, false, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 0, false, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 53, false, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 5, false, 31))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dealFeasibilityDependsOnPlayerCount() {
        GameSettings settings = new GameSettings(2, 20, true, 0);

        assertThat(settings.totalCards()).isEqualTo(108);
        assertThat(settings.maxCardsPerPlayer(5)).isEqualTo(21);
        assertThat(settings.canDeal(5)).isTrue();
        assertThat(settings.canDeal(6)).isFalse();
    }
}
