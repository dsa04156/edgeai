package io.edgeai.adapters.repository;
import io.edgeai.domain.remote.RemoteTarget;
import java.sql.*;
final class RemoteTargets {
    private RemoteTargets(){}
    static RemoteTarget read(ResultSet row) throws SQLException {
        return row.getString("remote_provider_key")==null?null:new RemoteTarget(row.getString("remote_provider_key"),row.getString("remote_configuration_digest"),row.getString("remote_source_mode"));
    }
    static String key(RemoteTarget t){return t==null?null:t.providerKey();}
    static String digest(RemoteTarget t){return t==null?null:t.configurationDigest();}
    static String source(RemoteTarget t){return t==null?null:t.sourceMode();}
}
