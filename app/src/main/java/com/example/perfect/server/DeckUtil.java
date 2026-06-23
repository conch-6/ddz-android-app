package com.example.perfect.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class DeckUtil {

    private static final String[] SUITS = {"♠", "♥", "♣", "♦"};
    private static final String[] VALUES = {"3","4","5","6","7","8","9","10","J","Q","K","A","2"};
    private static final Random RANDOM = new Random();

    public static List<Card> createDeck() {
        List<Card> deck = new ArrayList<>();
        int id = 0;
        for (String suit : SUITS) {
            for (String val : VALUES) {
                String color = (suit.equals("♥") || suit.equals("♦")) ? "color-red" : "color-black";
                deck.add(new Card(id++, val, suit, color));
            }
        }
        deck.add(new Card(id++, "小", "🃏", "color-black"));
        deck.add(new Card(id++, "大", "🃏", "color-red"));
        return deck;
    }

    public static List<Card> shuffleDeck(List<Card> deck) {
        List<Card> arr = new ArrayList<>(deck);
        for (int i = arr.size() - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            Collections.swap(arr, i, j);
        }
        return arr;
    }

    /**
     * 发牌：随机移除15张，剩余39张分给2位玩家（每人18张，剩余3张不用）
     * 返回 { hands: { playerId: [Card, ...] }, firstTurn: playerId }
     */
    public static Map<String, Object> dealCards(List<String> playerIds) {
        List<Card> deck = shuffleDeck(createDeck());

        // 随机移除 15 张
        List<Integer> removed = new ArrayList<>();
        while (removed.size() < 15) {
            int idx = RANDOM.nextInt(deck.size());
            if (!removed.contains(idx)) {
                removed.add(idx);
            }
        }

        List<Card> remaining = new ArrayList<>();
        for (int i = 0; i < deck.size(); i++) {
            if (!removed.contains(i)) {
                remaining.add(deck.get(i));
            }
        }

        Map<String, List<Card>> hands = new LinkedHashMap<>();
        for (int i = 0; i < playerIds.size(); i++) {
            String pid = playerIds.get(i);
            List<Card> hand = new ArrayList<>(remaining.subList(i * 18, (i + 1) * 18));
            hands.put(pid, hand);
        }

        String firstTurn = playerIds.get(RANDOM.nextInt(playerIds.size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hands", hands);
        result.put("firstTurn", firstTurn);
        return result;
    }
}
