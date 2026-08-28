package com.minikun.visual;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InspirationBoardStore {
    InspirationBoard saveBoard(InspirationBoard board);
    Optional<InspirationBoard> findBoard(String ownerId, UUID boardId);
    List<InspirationBoard> listBoards(String ownerId, int limit);
    boolean deleteBoard(String ownerId, UUID boardId);
    InspirationBoardItem saveItem(InspirationBoardItem item);
    List<InspirationBoardItem> listItems(UUID boardId, int limit);
    boolean deleteItem(UUID boardId, UUID itemId);
}
