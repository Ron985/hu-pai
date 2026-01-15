package com.bluff.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Card {
    private int value; // 1-13 (A-K), 14 (小王), 15 (大王)
    private String suit; // SPADE, HEART, CLUB, DIAMOND, JOKER

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Card card = (Card) o;
        return value == card.value && Objects.equals(suit, card.suit);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, suit);
    }
}
