package org.xianshen.mumirrorb.common.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;
import java.util.UUID;

/**
 * String ↔ UUID 类型处理器
 *
 * 解决 Java String 与 PostgreSQL UUID 类型不匹配的问题。
 * 写入时：String → UUID（setObject 自动处理）
 * 读取时：UUID → String（调用 toString）
 */

public class StringToUuidTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, UUID.fromString(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        UUID uuid = rs.getObject(columnName, UUID.class);
        return uuid != null ? uuid.toString() : null;
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        UUID uuid = rs.getObject(columnIndex, UUID.class);
        return uuid != null ? uuid.toString() : null;
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        UUID uuid = cs.getObject(columnIndex, UUID.class);
        return uuid != null ? uuid.toString() : null;
    }
}
