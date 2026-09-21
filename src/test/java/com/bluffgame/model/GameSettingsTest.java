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
        assertThat(settings.turnSeconds()).isEqualTo(30);
    }

    @Test
    void rejectsImpossibleValues() {
        assertThatThrownBy(() -> new GameSettings(0, 5, false, 5, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 0, false, 5, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 53, false, 5, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 5, false, 31, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameSettings(1, 5, false, 0, 5, false))
                .isInstanceOf(IllegalArgumentException.class); // below the ten second minimum
        assertThatThrownBy(() -> new GameSettings(1, 5, false, 20, 20, false))
                .isInstanceOf(IllegalArgumentException.class); // not longer than the call window
        assertThatThrownBy(() -> new GameSettings(1, 5, false, 0, 301, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitEquallySharesTheWholePileAtDealTime() {
        GameSettings split = new GameSettings(1, 1, true, 0, 0, true);

        assertThat(split.effective(4).cardsPerPlayer()).isEqualTo(13);
        assertThat(split.effective(5).cardsPerPlayer()).isEqualTo(10);
        assertThat(split.canDeal(50)).isTrue();
        assertThat(new GameSettings(1, 20, true, 0, 0, false).effective(4).cardsPerPlayer()).isEqualTo(20);
    }

    @Test
    void dealFeasibilityDependsOnPlayerCount() {
        GameSettings settings = new GameSettings(2, 20, true, 0, 0, false);

        assertThat(settings.totalCards()).isEqualTo(108);
        assertThat(settings.maxCardsPerPlayer(5)).isEqualTo(21);
        assertThat(settings.canDeal(5)).isTrue();
        assertThat(settings.canDeal(6)).isFalse();
    }
}
