package com.minikun.visual;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryInspirationBoardStore implements InspirationBoardStore {
    private final Map<UUID, InspirationBoard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, InspirationBoardItem> items = new ConcurrentHashMap<>();
    public InspirationBoard saveBoard(InspirationBoard board) { boards.put(board.id(), board); return board; }
    public Optional<InspirationBoard> findBoard(String ownerId, UUID boardId) {
        return Optional.ofNullable(boards.get(boardId)).filter(value -> value.ownerId().equals(ownerId));
    }
    public List<InspirationBoard> listBoards(String ownerId, int limit) {
        return boards.values().stream().filter(value -> value.ownerId().equals(ownerId))
                .sorted(Comparator.comparing(InspirationBoard::updatedAt).reversed())
                .limit(Math.max(0, limit)).toList();
    }
    public boolean deleteBoard(String ownerId, UUID boardId) {
        if (findBoard(ownerId, boardId).isEmpty()) return false;
        boards.remove(boardId); items.values().removeIf(item -> item.boardId().equals(boardId)); return true;
    }
    public InspirationBoardItem saveItem(InspirationBoardItem item) { items.put(item.id(), item); return item; }
    public List<InspirationBoardItem> listItems(UUID boardId, int limit) {
        return items.values().stream().filter(item -> item.boardId().equals(boardId))
                .sorted(Comparator.comparing(InspirationBoardItem::createdAt).reversed())
                .limit(Math.max(0, limit)).toList();
    }
    public boolean deleteItem(UUID boardId, UUID itemId) {
        InspirationBoardItem item = items.get(itemId);
        return item != null && item.boardId().equals(boardId) && items.remove(itemId, item);
    }
}
