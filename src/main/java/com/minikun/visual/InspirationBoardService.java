package com.minikun.visual;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InspirationBoardService {
    private final InspirationBoardStore store;
    private final Clock clock;
    public InspirationBoardService(InspirationBoardStore store, Clock clock) { this.store = store; this.clock = clock; }
    public List<InspirationBoard> list(String ownerId, int limit) { return store.listBoards(owner(ownerId), bound(limit)); }
    public InspirationBoard create(String ownerId, String title) {
        Instant now = clock.instant();
        return store.saveBoard(new InspirationBoard(UUID.randomUUID(), owner(ownerId), text(title,"title",120), now, now));
    }
    public boolean delete(String ownerId, UUID id) { return store.deleteBoard(owner(ownerId), id); }
    public List<InspirationBoardItem> items(String ownerId, UUID boardId, int limit) {
        board(ownerId, boardId); return store.listItems(boardId, bound(limit));
    }
    public InspirationBoardItem add(String ownerId, UUID boardId, String imageUrl, String sourceUrl,
            String title, String description, String origin) {
        InspirationBoard board = board(ownerId, boardId);
        String image = imageUrl(imageUrl);
        InspirationBoardItem item = new InspirationBoardItem(UUID.randomUUID(), boardId, image,
                optionalUrl(sourceUrl), clip(title,240), clip(description,1000), clip(origin,24), clock.instant());
        store.saveBoard(new InspirationBoard(board.id(), board.ownerId(), board.title(), board.createdAt(), clock.instant()));
        return store.saveItem(item);
    }
    public boolean remove(String ownerId, UUID boardId, UUID itemId) { board(ownerId, boardId); return store.deleteItem(boardId,itemId); }
    private InspirationBoard board(String ownerId, UUID id) {
        return store.findBoard(owner(ownerId), id).orElseThrow(() -> new IllegalArgumentException("board not found"));
    }
    private String owner(String value) { return value == null || value.isBlank() ? "default" : clip(value.trim(),255); }
    private String text(String value,String name,int max) {
        String result=clip(value,max); if(result.isBlank()) throw new IllegalArgumentException(name+" is required"); return result;
    }
    private String clip(String value,int max) { String result=value==null?"":value.trim(); return result.length()>max?result.substring(0,max):result; }
    private int bound(int limit) { return Math.max(1, Math.min(200, limit)); }
    private String optionalUrl(String value) { return value == null || value.isBlank() ? "" : url(value,"source_url"); }
    private String imageUrl(String value) {
        String result = text(value, "image_url", 4000);
        if (result.startsWith("/") && !result.startsWith("//")) return result;
        return url(result, "image_url");
    }
    private String url(String value,String name) {
        String result=text(value,name,4000);
        try { URI uri=URI.create(result); if(uri.getHost()==null || !("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException exception){ throw new IllegalArgumentException(name+" must be an HTTP(S) URL"); }
        return result;
    }
}
