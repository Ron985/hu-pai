package com.bluff.game.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.bluff.game.manager.RoomManager;
import com.bluff.game.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private RoomManager roomManager;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToUser = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String userId = sessionToUser.get(session.getId());
        if (userId != null) {
            handleUserLeave(userId);
            sessions.remove(userId);
            sessionToUser.remove(session.getId());
        }
    }

    public void handleUserLeave(String userId) throws IOException {
        for (GameRoom room : roomManager.getAllRooms()) {
            boolean removed = room.getPlayers().removeIf(p -> p.getUserId().equals(userId));
            if (removed) {
                if ("PLAYING".equals(room.getStatus())) {
                    // 如果是在游戏中被强制清理（比如匹配新房间），这种场景其实不应该发生，
                    // 但如果发生了，我们还是按掉线处理逻辑走一下
                    long onlineCount = room.getPlayers().stream().filter(Player::isOnline).count();
                    if (onlineCount <= 1) {
                        endGameWithWinner(room);
                    } else {
                        broadcast(room, "GAME_UPDATE", room);
                    }
                } else {
                    if (room.getPlayers().isEmpty()) {
                        roomManager.removeRoom(room.getRoomId());
                    } else {
                        // 重设房主
                        if (!room.getPlayers().stream().anyMatch(Player::isHost)) {
                            room.getPlayers().get(0).setHost(true);
                        }
                        room.setStatus("WAITING");
                        room.getPlayers().forEach(player -> player.setReady(false));
                        broadcast(room, "ROOM_UPDATE", room);
                    }
                }
                
                if (timers.containsKey(room.getRoomId()) && room.getPlayers().isEmpty()) {
                    timers.get(room.getRoomId()).cancel(false);
                    timers.remove(room.getRoomId());
                }
            }
        }
    }

    private void endGameWithWinner(GameRoom room) throws IOException {
        Player winner = room.getPlayers().stream().filter(Player::isOnline).findFirst().orElse(null);
        if (winner == null) {
            roomManager.removeRoom(room.getRoomId());
            return;
        }

        room.setStatus("FINISHED");
        List<Map<String, Object>> ranking = new ArrayList<>();
        Map<String, Object> m = new HashMap<>();
        m.put("nickname", winner.getNickname());
        m.put("userId", winner.getUserId());
        m.put("cardCount", winner.getHandCards().size());
        m.put("isWin", true);
        ranking.add(m);

        // 其他人按手牌数排序
        room.getPlayers().stream()
                .filter(p -> !p.getUserId().equals(winner.getUserId()))
                .sorted(Comparator.comparingInt(p -> p.getHandCards().size()))
                .forEach(p -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("nickname", p.getNickname());
                    map.put("userId", p.getUserId());
                    map.put("cardCount", p.getHandCards().size());
                    ranking.add(map);
                });

        Map<String, Object> result = new HashMap<>();
        result.put("ranking", ranking);
        result.put("reason", "其他玩家已退出");
        broadcast(room, "GAME_OVER", result);

        resetRoomState(room);
    }

    private void resetRoomState(GameRoom room) throws IOException {
        room.getPlayers().forEach(p -> {
            p.setReady(false);
            p.getHandCards().clear();
        });
        room.getDeskPile().clear();
        room.setLastClaimedRank(null);
        room.setLastPlayerId(null);
        room.setLastPlayedCards(new ArrayList<>());
        if (timers.containsKey(room.getRoomId())) {
            timers.get(room.getRoomId()).cancel(false);
            timers.remove(room.getRoomId());
        }
        // 关键修复：广播房间更新，确保所有人回到房间后看到的是未准备状态
        broadcast(room, "ROOM_UPDATE", room);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JSONObject msg = JSON.parseObject(message.getPayload());
        String type = msg.getString("type");
        String userId = msg.getString("userId");
        String roomId = msg.getString("roomId");

        if (userId != null) {
            sessions.put(userId, session);
            sessionToUser.put(session.getId(), userId);
        }

        switch (type) {
            case "PING": session.sendMessage(new TextMessage("{\"type\":\"PONG\"}")); break;
            case "JOIN": handleJoin(roomId, userId); break;
            case "READY": handleReady(roomId, userId); break;
            case "LEAVE": handleUserLeave(userId); break;
            case "PLAY": handlePlay(roomId, userId, msg); break;
            case "CHALLENGE": handleChallenge(roomId, userId); break;
            case "PASS": handlePass(roomId, userId); break;
        }
    }

    private void handleJoin(String roomId, String userId) throws IOException {
        GameRoom room = roomManager.getRoom(roomId);
        if (room != null) {
            room.getPlayers().stream()
                    .filter(p -> p.getUserId().equals(userId))
                    .findFirst()
                    .ifPresent(p -> p.setOnline(true));
            broadcast(room, "ROOM_UPDATE", room);
        }
    }

    private void handleReady(String roomId, String userId) throws IOException {
        GameRoom room = roomManager.getRoom(roomId);
        if (room == null) return;
        room.getPlayers().forEach(p -> { if (p.getUserId().equals(userId)) p.setReady(!p.isReady()); });
        if (room.getPlayers().size() == 3 && room.getPlayers().stream().allMatch(Player::isReady)) {
            startGame(room);
        } else {
            broadcast(room, "ROOM_UPDATE", room);
        }
    }

    private void startGame(GameRoom room) throws IOException {
        room.setStatus("PLAYING");
        List<Card> deck = createFullDeck();
        Collections.shuffle(deck);
        for (int i = 0; i < deck.size(); i++) {
            room.getPlayers().get(i % 3).getHandCards().add(deck.get(i));
        }
        room.getPlayers().forEach(p -> p.setOnline(true));
        room.setCurrentPlayerIndex(new Random().nextInt(3));
        room.setDeskPile(new ArrayList<>());
        room.setLastClaimedRank(null);
        room.setLastPlayerId(null);
        broadcast(room, "GAME_START", room);
        resetTurnTimer(room);
    }

    private List<Card> createFullDeck() {
        List<Card> deck = new ArrayList<>();
        String[] suits = {"SPADE", "HEART", "CLUB", "DIAMOND"};
        for (String suit : suits) {
            for (int i = 1; i <= 13; i++) deck.add(new Card(i, suit));
        }
        deck.add(new Card(14, "JOKER"));
        deck.add(new Card(15, "JOKER"));
        return deck;
    }

    private void resetTurnTimer(GameRoom room) {
        String roomId = room.getRoomId();
        if (timers.containsKey(roomId)) {
            timers.get(roomId).cancel(false);
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                GameRoom currentRoom = roomManager.getRoom(roomId);
                if (currentRoom != null && "PLAYING".equals(currentRoom.getStatus())) {
                    Player p = currentRoom.getPlayers().get(currentRoom.getCurrentPlayerIndex());
                    System.out.println("用户 " + p.getNickname() + " 超时，系统自动过牌");
                    handlePass(roomId, p.getUserId());
                }
            } catch (Exception e) { e.printStackTrace(); }
        }, 30, TimeUnit.SECONDS);
        timers.put(roomId, future);
    }

    private void moveToNextPlayer(GameRoom room) {
        int nextIndex = (room.getCurrentPlayerIndex() + 1) % room.getPlayers().size();
        // 最多循环一圈找到在线玩家
        for (int i = 0; i < room.getPlayers().size(); i++) {
            if (room.getPlayers().get(nextIndex).isOnline()) {
                room.setCurrentPlayerIndex(nextIndex);
                return;
            }
            nextIndex = (nextIndex + 1) % room.getPlayers().size();
        }
    }

    private void handlePlay(String roomId, String userId, JSONObject msg) throws IOException {
        GameRoom room = roomManager.getRoom(roomId);
        if (room == null || !"PLAYING".equals(room.getStatus())) return;
        // 校验是否轮到该玩家
        if (!room.getPlayers().get(room.getCurrentPlayerIndex()).getUserId().equals(userId)) return;

        List<Card> playedCards = msg.getList("cards", Card.class);
        String claimedRank = msg.getString("claimedRank");

        room.getDeskPile().addAll(playedCards);
        room.setLastPlayedCards(playedCards);
        room.setLastClaimedRank(claimedRank);
        room.setLastPlayerId(userId);
        
        Player current = room.getPlayers().get(room.getCurrentPlayerIndex());
        current.getHandCards().removeIf(playedCards::contains);

        moveToNextPlayer(room);
        broadcast(room, "GAME_UPDATE", room);
        resetTurnTimer(room);
    }

    private void handlePass(String roomId, String userId) throws IOException {
        GameRoom room = roomManager.getRoom(roomId);
        if (room == null || !"PLAYING".equals(room.getStatus())) return;
        // 只有当前回合的人能过
        if (!room.getPlayers().get(room.getCurrentPlayerIndex()).getUserId().equals(userId)) return;
        
        moveToNextPlayer(room);
        
        // 如果回到了最后出牌人（或者该人已经掉线），且桌上有牌，清空桌面
        Player nextPlayer = room.getPlayers().get(room.getCurrentPlayerIndex());
        if (nextPlayer.getUserId().equals(room.getLastPlayerId()) || !nextPlayer.isOnline()) {
            if (checkAndEndGame(room)) return;
            room.getDeskPile().clear();
            room.setLastClaimedRank(null);
            room.setLastPlayerId(null);
        }
        
        broadcast(room, "GAME_UPDATE", room);
        resetTurnTimer(room);
    }

    private void handleChallenge(String roomId, String challengerId) throws IOException {
        GameRoom room = roomManager.getRoom(roomId);
        if (room == null || room.getLastPlayerId() == null) return;

        List<Card> lastPlayed = room.getLastPlayedCards();
        int targetValue = parseRank(room.getLastClaimedRank());

        boolean isLying = lastPlayed.stream().anyMatch(c -> c.getValue() != targetValue && c.getValue() < 14);

        String lastPlayerId = room.getLastPlayerId();
        String loserId = isLying ? lastPlayerId : challengerId;
        Player loser = room.getPlayers().stream().filter(p -> p.getUserId().equals(loserId)).findFirst().get();
        loser.getHandCards().addAll(room.getDeskPile());
        
        room.getDeskPile().clear();
        room.setLastClaimedRank(null);
        room.setLastPlayerId(null);
        
        if (!checkAndEndGame(room)) {
            // 质疑成功，质疑者开始；质疑失败，被质疑者（赢家）开始
            String nextPlayerId = isLying ? challengerId : lastPlayerId;
            
            for (int i = 0; i < room.getPlayers().size(); i++) {
                if (room.getPlayers().get(i).getUserId().equals(nextPlayerId)) {
                    room.setCurrentPlayerIndex(i);
                    break;
                }
            }
            
            // 如果选中的玩家离线了，跳到下一个
            if (!room.getPlayers().get(room.getCurrentPlayerIndex()).isOnline()) {
                room.setCurrentPlayerIndex((room.getCurrentPlayerIndex() + 1) % room.getPlayers().size());
            }

            Map<String, Object> result = new HashMap<>();
            result.put("isLying", isLying);
            result.put("loserId", loserId);
            result.put("challengerId", challengerId);
            result.put("lastPlayerId", lastPlayerId);
            result.put("room", room);
            broadcast(room, "CHALLENGE_RESULT", result);
            resetTurnTimer(room);
        }
    }

    private boolean checkAndEndGame(GameRoom room) throws IOException {
        boolean hasWinner = room.getPlayers().stream().anyMatch(p -> p.getHandCards().isEmpty());
        if (hasWinner) {
            endGame(room);
            return true;
        }
        return false;
    }

    private void endGame(GameRoom room) throws IOException {
        room.setStatus("FINISHED");
        List<Map<String, Object>> ranking = room.getPlayers().stream()
                .sorted(Comparator.comparingInt(p -> p.getHandCards().size()))
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("nickname", p.getNickname());
                    m.put("userId", p.getUserId());
                    m.put("cardCount", p.getHandCards().size());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("ranking", ranking);
        broadcast(room, "GAME_OVER", result);

        resetRoomState(room);
    }

    private int parseRank(String rank) {
        if ("A".equals(rank)) return 1;
        if ("J".equals(rank)) return 11;
        if ("Q".equals(rank)) return 12;
        if ("K".equals(rank)) return 13;
        try { return Integer.parseInt(rank); } catch (Exception e) { return 0; }
    }

    private void broadcast(GameRoom room, String type, Object data) {
        Map<String, Object> packet = new HashMap<>();
        packet.put("type", type);
        packet.put("payload", data);
        String json = JSON.toJSONString(packet);
        for (Player p : room.getPlayers()) {
            WebSocketSession s = sessions.get(p.getUserId());
            if (s != null && s.isOpen()) {
                try {
                    synchronized (s) {
                        s.sendMessage(new TextMessage(json));
                    }
                } catch (IOException e) {
                    System.err.println("广播失败: " + e.getMessage());
                }
            }
        }
    }
}
