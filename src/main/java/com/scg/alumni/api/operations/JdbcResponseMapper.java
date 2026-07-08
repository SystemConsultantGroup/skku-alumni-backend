package com.scg.alumni.api.operations;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;

final class JdbcResponseMapper implements RowMapper<Map<String, Object>> {

    static final JdbcResponseMapper INSTANCE = new JdbcResponseMapper();

    private JdbcResponseMapper() {
    }

    @Override
    public Map<String, Object> mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();

        for (int column = 1; column <= metaData.getColumnCount(); column++) {
            row.put(toLowerCamel(metaData.getColumnLabel(column)), resultSet.getObject(column));
        }

        return row;
    }

    private String toLowerCamel(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;

        for (int i = 0; i < lower.length(); i++) {
            char character = lower.charAt(i);
            if (character == '_') {
                upperNext = true;
                continue;
            }
            if (upperNext) {
                builder.append(Character.toUpperCase(character));
                upperNext = false;
                continue;
            }
            builder.append(character);
        }

        return builder.toString();
    }
}
