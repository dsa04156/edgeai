package io.edgeai.adapters.profile;

import io.edgeai.domain.profile.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public final class JdbcProfileRepository implements ProfileRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ProfileVersion> ROW = (rs, n) -> new ProfileVersion(
        rs.getObject("id", UUID.class),
        new ProfileIdentity(ProfileIdentity.Kind.valueOf(rs.getString("kind")),
            rs.getString("profile_key"), rs.getString("version")),
        rs.getString("spec"), rs.getString("digest"), rs.getTimestamp("created_at").toInstant());

    public JdbcProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Publication publish(ProfileIdentity identity, String spec, String digest) {
        int inserted = jdbc.update("""
            INSERT INTO edgeai.profile_version(id, kind, profile_key, version, spec, digest)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
            ON CONFLICT (kind, profile_key, version) DO NOTHING
            """, UUID.randomUUID(), identity.kind().name(), identity.key(), identity.version(), spec, digest);
        // A separate statement sees the committed winning row after a concurrent conflict.
        return new Publication(find(identity).orElseThrow(), inserted == 1);
    }

    @Override
    public Optional<ProfileVersion> find(ProfileIdentity identity) {
        return jdbc.query("""
            SELECT * FROM edgeai.profile_version WHERE kind = ? AND profile_key = ? AND version = ?
            """, ROW, identity.kind().name(), identity.key(), identity.version()).stream().findFirst();
    }

    @Override
    public List<ProfileVersion> list(ProfileIdentity.Kind kind, String key, int limit, int offset) {
        String filter = key == null ? "" : " AND profile_key = ?";
        String sql = "SELECT * FROM edgeai.profile_version WHERE kind = ?" + filter +
            " ORDER BY profile_key COLLATE \"C\", version COLLATE \"C\" LIMIT ? OFFSET ?";
        return key == null ? jdbc.query(sql, ROW, kind.name(), limit, offset)
            : jdbc.query(sql, ROW, kind.name(), key, limit, offset);
    }
}
