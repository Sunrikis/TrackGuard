package cn.rfoid.repository;

import cn.rfoid.api.ApiException;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    public record Account(String id, String username, String displayName, String passwordHash, String avatarFile) {}
    private final JdbcTemplate db;
    public AccountRepository(JdbcTemplate db) { this.db = db; }

    private List<Account> find(String column, String value) {
        return db.query("SELECT id,username,display_name,password_hash,avatar_file FROM app_user WHERE " + column + "=?",
            (rs, n) -> new Account(rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("password_hash"), rs.getString("avatar_file")), value);
    }
    public Account byUsername(String username) { return find("username", username).stream().findFirst().orElse(null); }
    public Account get(String id) {
        return find("id", id).stream().findFirst().orElseThrow(() -> new ApiException(401, "LOGIN_REQUIRED", "请重新登录"));
    }
    public Account create(String username, String displayName, String hash) {
        String id = UUID.randomUUID().toString();
        try { db.update("INSERT INTO app_user(id,username,display_name,password_hash) VALUES(?,?,?,?)", id, username, displayName, hash); }
        catch (DuplicateKeyException e) { throw new ApiException(409, "USERNAME_EXISTS", "该用户名已注册，请更换或直接登录"); }
        return get(id);
    }
    public void avatar(String id, String filename) { db.update("UPDATE app_user SET avatar_file=? WHERE id=?", filename, id); }
}
