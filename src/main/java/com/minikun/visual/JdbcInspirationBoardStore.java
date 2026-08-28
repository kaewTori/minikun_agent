package com.minikun.visual;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcInspirationBoardStore implements InspirationBoardStore {
    private final JdbcTemplate jdbc;
    public JdbcInspirationBoardStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    public InspirationBoard saveBoard(InspirationBoard board) {
        jdbc.update("""
                INSERT INTO minikun_inspiration_board (id, owner_id, title, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title, updated_at=EXCLUDED.updated_at
                """, board.id(), board.ownerId(), board.title(), Timestamp.from(board.createdAt()),
                Timestamp.from(board.updatedAt()));
        return board;
    }
    public Optional<InspirationBoard> findBoard(String ownerId, UUID boardId) {
        return jdbc.query("SELECT id,owner_id,title,created_at,updated_at FROM minikun_inspiration_board "
                + "WHERE owner_id=? AND id=?", this::board, ownerId, boardId).stream().findFirst();
    }
    public List<InspirationBoard> listBoards(String ownerId, int limit) {
        return jdbc.query("SELECT id,owner_id,title,created_at,updated_at FROM minikun_inspiration_board "
                + "WHERE owner_id=? ORDER BY updated_at DESC LIMIT ?", this::board, ownerId, limit);
    }
    public boolean deleteBoard(String ownerId, UUID boardId) {
        return jdbc.update("DELETE FROM minikun_inspiration_board WHERE owner_id=? AND id=?", ownerId, boardId) > 0;
    }
    public InspirationBoardItem saveItem(InspirationBoardItem item) {
        jdbc.update("""
                INSERT INTO minikun_inspiration_board_item
                    (id,board_id,image_url,source_url,title,description,origin,created_at)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING
                """, item.id(), item.boardId(), item.imageUrl(), item.sourceUrl(), item.title(),
                item.description(), item.origin(), Timestamp.from(item.createdAt()));
        return item;
    }
    public List<InspirationBoardItem> listItems(UUID boardId, int limit) {
        return jdbc.query("SELECT id,board_id,image_url,source_url,title,description,origin,created_at "
                + "FROM minikun_inspiration_board_item WHERE board_id=? ORDER BY created_at DESC LIMIT ?",
                this::item, boardId, limit);
    }
    public boolean deleteItem(UUID boardId, UUID itemId) {
        return jdbc.update("DELETE FROM minikun_inspiration_board_item WHERE board_id=? AND id=?", boardId, itemId) > 0;
    }
    private InspirationBoard board(ResultSet rs, int row) throws SQLException {
        return new InspirationBoard(uuid(rs, "id"), rs.getString("owner_id"), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private InspirationBoardItem item(ResultSet rs, int row) throws SQLException {
        return new InspirationBoardItem(uuid(rs,"id"), uuid(rs,"board_id"), rs.getString("image_url"),
                rs.getString("source_url"), rs.getString("title"), rs.getString("description"),
                rs.getString("origin"), rs.getTimestamp("created_at").toInstant());
    }
    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column); return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }
}
