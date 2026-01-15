package com.bluff.game.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class Player {
    private String userId;
    private String nickname;
    private List<Card> handCards = new ArrayList<>();
    private boolean isReady = false;
    private boolean isHost = false;
    private boolean online = true;
    
    // 隐藏手牌详细内容，只返回张数给其他玩家
    public int getCardCount() {
        return handCards.size();
    }
}
