package org.xianshen.mumirrorb.common.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;
import java.sql.*;

@MappedTypes(JsonNode.class)
public class JsonNodeTypeHandler extends BaseTypeHandler<JsonNode> {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override public void setNonNullParameter(PreparedStatement ps, int i, JsonNode value, JdbcType type) throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(value.toString());
        ps.setObject(i, json);
    }
    @Override public JsonNode getNullableResult(ResultSet rs, String name) throws SQLException { return parse(rs.getString(name)); }
    @Override public JsonNode getNullableResult(ResultSet rs, int index) throws SQLException { return parse(rs.getString(index)); }
    @Override public JsonNode getNullableResult(CallableStatement cs, int index) throws SQLException { return parse(cs.getString(index)); }
    private JsonNode parse(String value) throws SQLException {
        if (value == null) return null;
        try { return JSON.readTree(value); }
        catch (JsonProcessingException e) { throw new SQLException("Invalid stored action JSON", e); }
    }
}
