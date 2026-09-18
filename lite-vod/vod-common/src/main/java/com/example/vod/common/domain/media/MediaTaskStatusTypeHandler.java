package com.example.vod.common.domain.media;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * media_task.status 与 {@link MediaTaskStatus} 的映射：数据库存 int code。
 */
@MappedTypes(MediaTaskStatus.class)
public class MediaTaskStatusTypeHandler extends BaseTypeHandler<MediaTaskStatus> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, MediaTaskStatus parameter, JdbcType jdbcType) throws SQLException {
        ps.setInt(i, parameter.code());
    }

    @Override
    public MediaTaskStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int code = rs.getInt(columnName);
        return rs.wasNull() ? null : MediaTaskStatus.of(code);
    }

    @Override
    public MediaTaskStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int code = rs.getInt(columnIndex);
        return rs.wasNull() ? null : MediaTaskStatus.of(code);
    }

    @Override
    public MediaTaskStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int code = cs.getInt(columnIndex);
        return cs.wasNull() ? null : MediaTaskStatus.of(code);
    }
}
