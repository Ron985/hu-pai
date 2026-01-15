package com.bluff.game.controller;

import com.bluff.game.manager.RoomManager;
import com.bluff.game.model.GameRoom;
import com.bluff.game.model.Player;
import com.bluff.game.websocket.GameWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/room")
public class RoomController {

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private GameWebSocketHandler webSocketHandler;

    private void cleanupPlayerFromRooms(String userId) {
        try {
            webSocketHandler.handleUserLeave(userId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @PostMapping("/create")
    public GameRoom createRoom(@RequestBody Player host) {
        cleanupPlayerFromRooms(host.getUserId());
        String roomId = String.valueOf((int)((Math.random() * 9 + 1) * 1000));
        GameRoom room = roomManager.createRoom(roomId);
        host.setHost(true);
        host.setOnline(false);
        host.setLastSeen(System.currentTimeMillis());
        room.addPlayer(host);
        return room;
    }

    @PostMapping("/join")
    public GameRoom joinRoom(@RequestParam String roomId, @RequestBody Player player) {
        cleanupPlayerFromRooms(player.getUserId());
        GameRoom room = roomManager.getRoom(roomId);
        if (room != null) {
            cleanupZombiePlayers(room);
            if (room.getPlayers().size() < 3) {
                player.setOnline(false);
                player.setLastSeen(System.currentTimeMillis());
                room.addPlayer(player);
                return room;
            }
        }
        throw new RuntimeException("Room not found or full");
    }

    @PostMapping("/match")
    public GameRoom quickMatch(@RequestBody Player player) {
        cleanupPlayerFromRooms(player.getUserId());
        GameRoom room = roomManager.findMatch();
        if (room != null) {
            cleanupZombiePlayers(room);
            if (room.getPlayers().size() < 3) {
                player.setOnline(false);
                player.setLastSeen(System.currentTimeMillis());
                room.addPlayer(player);
                return room;
            }
        }
        return createRoom(player);
    }

    private void cleanupZombiePlayers(GameRoom room) {
        if (room == null) return;
        long now = System.currentTimeMillis();
        room.getPlayers().removeIf(p -> {
            // 如果 WebSocket 在线，肯定不是僵尸
            if (webSocketHandler.isUserOnline(p.getUserId())) return false;
            // 如果不在线，但刚进来不到 10 秒（可能正在建立连接），暂时保留
            return (now - p.getLastSeen() > 10000);
        });
    }
}
