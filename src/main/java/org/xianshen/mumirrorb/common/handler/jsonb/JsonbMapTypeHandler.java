package org.xianshen.mumirrorb.common.handler.jsonb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * PostgreSQL JSONB 类型与 Java Map<String, Object> 的映射处理器
 *
 * <p>用途：chunks 表的 metadata 字段在数据库中是 JSONB 类型，
 *       Java 侧映射为 Map<String, Object>，MyBatis-Plus 自动调用此 Handler 做序列化/反序列化。</p>
 *
 * <p>与 {@link JsonbTypeHandler} 的区别：</p>
 * <ul>
 *   <li>JsonbTypeHandler：处理 List&lt;String&gt; 类型（如 mood 字段）</li>
 *   <li>JsonbMapTypeHandler：处理 Map&lt;String, Object&gt; 类型（如 metadata 字段）</li>
 * </ul>
 */
@MappedTypes(Map.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbMapTypeHandler extends BaseTypeHandler<Map<String, Object>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 设置参数：Java Map → PostgreSQL JSONB
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, Object> parameter, JdbcType jdbcType) throws SQLException {
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue(OBJECT_MAPPER.writeValueAsString(parameter));
            ps.setObject(i, pgObject);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSONB 序列化失败", e);
        }
    }

    /**
     * 根据列名获取：PostgreSQL JSONB → Java Map
     */
    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJsonb(rs.getString(columnName));
    }

    /**
     * 根据列索引获取：PostgreSQL JSONB → Java Map
     */
    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJsonb(rs.getString(columnIndex));
    }

    /**
     * 存储过程获取：PostgreSQL JSONB → Java Map
     */
    @Override
    public Map<String, Object> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJsonb(cs.getString(columnIndex));
    }

    /**
     * JSON 字符串反序列化为 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonb(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSONB 反序列化失败: " + json, e);
        }
    }
}
