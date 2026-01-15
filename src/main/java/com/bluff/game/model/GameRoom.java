package com.bluff.game.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
public class GameRoom {
    private String roomId;
    private List<Player> players = new CopyOnWriteArrayList<>();
    private List<Card> deskPile = new ArrayList<>(); // 桌面上的扣牌
    private String status = "WAITING"; // WAITING, PLAYING, FINISHED
    private int currentPlayerIndex = 0;
    private String lastClaimedRank; // 上一次声明的点数
    private List<Card> lastPlayedCards = new ArrayList<>(); // 上一次实际出的牌
    private String lastPlayerId; // 上一个出牌的人

    public void addPlayer(Player player) {
        // 先检查是否已经在房间里了（比如掉线重连）
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getUserId().equals(player.getUserId())) {
                players.set(i, player); // 替换旧的玩家对象
                return;
            }
        }
        
        if (players.size() < 3) {
            players.add(player);
        }
    }
}
