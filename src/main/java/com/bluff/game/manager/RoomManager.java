package com.bluff.game.manager;

import com.bluff.game.model.GameRoom;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoomManager {
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();

    public GameRoom createRoom(String roomId) {
        GameRoom room = new GameRoom();
        room.setRoomId(roomId);
        rooms.put(roomId, room);
        return room;
    }

    public GameRoom getRoom(String roomId) {
        return rooms.get(roomId);
    }

    public void removeRoom(String roomId) {
        rooms.remove(roomId);
    }

    public Collection<GameRoom> getAllRooms() {
        return rooms.values();
    }
    
    public GameRoom findMatch() {
        return rooms.values().stream()
                .filter(r -> "WAITING".equals(r.getStatus()) && r.getPlayers().size() < 3)
                .findFirst()
                .orElse(null);
    }
}
