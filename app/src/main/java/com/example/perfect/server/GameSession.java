package com.example.perfect.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GameSession {
    public String id;
    public List<String> players;
    public Map<String, List<Card>> hands;  // { playerId: [Card, ...] }
    public String currentTurn;              // 当前轮到的玩家 ID
    public List<Card> lastPlay;             // 最后一次出牌列表
    public String lastPlayPlayer;           // 最后一次出牌的玩家
    public String winner;                   // 胜者 ID
    public Map<String, Boolean> online;     // { playerId: bool }
    public String phase;                    // WAITING | PLAYING | GAME_OVER
    public Set<String> dealRequests;
    public Set<String> continueRequests;

    public GameSession(String playerA, String playerB) {
        this.id = playerA + "_" + playerB + "_" + System.currentTimeMillis();
        this.players = new ArrayList<>(Arrays.asList(playerA, playerB));
        this.hands = new LinkedHashMap<>();
        this.currentTurn = null;
        this.lastPlay = null;
        this.lastPlayPlayer = null;
        this.winner = null;
        this.online = new LinkedHashMap<>();
        this.online.put(playerA, false);
        this.online.put(playerB, false);
        this.phase = "WAITING";
        this.dealRequests = ConcurrentHashMap.newKeySet();
        this.continueRequests = ConcurrentHashMap.newKeySet();
    }

    public String opponent(String playerId) {
        for (String p : players) {
            if (!p.equals(playerId)) return p;
        }
        return null;
    }

    public boolean bothOnline() {
        for (String p : players) {
            if (!online.get(p)) return false;
        }
        return true;
    }

    public boolean bothOffline() {
        for (String p : players) {
            if (online.get(p)) return false;
        }
        return true;
    }
}
