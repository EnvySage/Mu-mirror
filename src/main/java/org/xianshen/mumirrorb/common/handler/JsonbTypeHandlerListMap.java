package org.xianshen.mumirrorb.common.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL JSONB 类型与 Java List&lt;Map&lt;String, Object&gt;&gt; 的映射处理器
 *
 * <p>用途：conversation_history 表的 sources 字段（JSONB 数组，元素为对象），
 * 如 {@code [{"record_id":1,"quote":"...","date":"2026-09-03"}]}。</p>
 *
 * <p>与 {@link JsonbTypeHandler}（List&lt;String&gt;）、
 * {@link org.xianshen.mumirrorb.common.handler.jsonb.JsonbMapTypeHandler}（Map）的区别：
 * 三者 MappedTypes 不同（List 接口重载需靠泛型区分，MyBatis 按字段声明类型路由）。</p>
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandlerListMap extends BaseTypeHandler<List<Map<String, Object>>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Map<String, Object>> parameter,
                                    JdbcType jdbcType) throws SQLException {
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue(OBJECT_MAPPER.writeValueAsString(parameter));
            ps.setObject(i, pgObject);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSONB 序列化失败", e);
        }
    }

    @Override
    public List<Map<String, Object>> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJsonb(rs.getString(columnName));
    }

    @Override
    public List<Map<String, Object>> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJsonb(rs.getString(columnIndex));
    }

    @Override
    public List<Map<String, Object>> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJsonb(cs.getString(columnIndex));
    }

    private List<Map<String, Object>> parseJsonb(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSONB 反序列化失败: " + json, e);
        }
    }
}
