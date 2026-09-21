package com.bluffgame.model;

/** How the announced rank works within a round. */
public enum RankMode {
    /** The player who opens a round picks the rank; everyone else in that round must claim the same rank. */
    ROUND,
    /** Every play announces its own rank, whatever came before. */
    FREE
}
