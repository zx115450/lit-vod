package com.example.vod.common.domain.media;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * media.status 与 {@link MediaStatus} 的映射：数据库存 int code。
 */
@MappedTypes(MediaStatus.class)
public class MediaStatusTypeHandler extends BaseTypeHandler<MediaStatus> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, MediaStatus parameter, JdbcType jdbcType) throws SQLException {
        ps.setInt(i, parameter.code());
    }

    @Override
    public MediaStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int code = rs.getInt(columnName);
        return rs.wasNull() ? null : MediaStatus.of(code);
    }

    @Override
    public MediaStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int code = rs.getInt(columnIndex);
        return rs.wasNull() ? null : MediaStatus.of(code);
    }

    @Override
    public MediaStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int code = cs.getInt(columnIndex);
        return cs.wasNull() ? null : MediaStatus.of(code);
    }
}
