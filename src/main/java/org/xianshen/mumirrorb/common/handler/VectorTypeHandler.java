package org.xianshen.mumirrorb.common.handler;

import com.pgvector.PGvector;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL vector 类型与 Java List<Float> 的映射处理器
 *
 * <p>用途：chunks 表的 embedding 字段在数据库中是 vector 类型（如 vector(1024)），
 *       Java 侧映射为 List<Float>，MyBatis-Plus 自动调用此 Handler 做序列化/反序列化。</p>
 *
 * <p>使用前需确保：</p>
 * <ul>
 *   <li>PostgreSQL 已安装 pgvector 扩展（CREATE EXTENSION IF NOT EXISTS vector;）</li>
 *   <li>pom.xml 已添加 pgvector-java 依赖</li>
 * </ul>
 */
@MappedTypes(List.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class VectorTypeHandler extends BaseTypeHandler<List<Float>> {

    /**
     * 设置参数：Java List<Float> → PostgreSQL vector
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<Float> parameter, JdbcType jdbcType) throws SQLException {
        float[] array = new float[parameter.size()];
        for (int j = 0; j < parameter.size(); j++) {
            array[j] = parameter.get(j) != null ? parameter.get(j) : 0f;
        }
        ps.setObject(i, new PGvector(array));
    }

    /**
     * 根据列名获取：PostgreSQL vector → Java List<Float>
     */
    @Override
    public List<Float> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getObject(columnName));
    }

    /**
     * 根据列索引获取：PostgreSQL vector → Java List<Float>
     */
    @Override
    public List<Float> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getObject(columnIndex));
    }

    /**
     * 存储过程获取：PostgreSQL vector → Java List<Float>
     */
    @Override
    public List<Float> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getObject(columnIndex));
    }

    /**
     * PGvector 对象转 List<Float>
     */
    private List<Float> toList(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof PGvector) {
            float[] array = ((PGvector) obj).toArray();
            List<Float> list = new ArrayList<>(array.length);
            for (float f : array) {
                list.add(f);
            }
            return list;
        }
        // 兜底：尝试解析字符串格式 "[1.0,2.0,3.0]"
        if (obj instanceof String) {
            String str = (String) obj;
            str = str.substring(1, str.length() - 1); // 去掉 []
            String[] parts = str.split(",");
            List<Float> list = new ArrayList<>(parts.length);
            for (String part : parts) {
                list.add(Float.parseFloat(part.trim()));
            }
            return list;
        }
        return null;
    }
}
