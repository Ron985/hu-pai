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
        room.addPlayer(host);
        return room;
    }

    @PostMapping("/join")
    public GameRoom joinRoom(@RequestParam String roomId, @RequestBody Player player) {
        cleanupPlayerFromRooms(player.getUserId());
        GameRoom room = roomManager.getRoom(roomId);
        if (room != null && room.getPlayers().size() < 3) {
            room.addPlayer(player);
            return room;
        }
        throw new RuntimeException("Room not found or full");
    }

    @PostMapping("/match")
    public GameRoom quickMatch(@RequestBody Player player) {
        cleanupPlayerFromRooms(player.getUserId());
        GameRoom room = roomManager.findMatch();
        if (room == null) {
            return createRoom(player);
        }
        room.addPlayer(player);
        return room;
    }
}
