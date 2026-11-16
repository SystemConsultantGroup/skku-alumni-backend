package com.scg.alumni.domain.organization;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AdministrativePersonService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(String name, String studentId, String phone) {
        String normalizedPhone = digits(phone);
        return jdbc.queryForList("""
                select id, name, student_id, phone, status from users where deleted_at is null and
                ((? is not null and student_id = ?) or (? is not null and
                  replace(replace(replace(coalesce(phone, ''), '-', ''), ' ', ''), '.', '') = ?)
                  or (? is not null and name = ?)) order by id desc limit 101
                """, nullable(studentId), nullable(studentId), normalizedPhone, normalizedPhone, nullable(name), nullable(name));
    }

    /** A candidate match is never silently merged; an administrator selects an existing user explicitly. */
    @Transactional
    public long create(String name, String studentId, String phone, boolean confirmedNoMatch) {
        if (!confirmedNoMatch) throw new ResponseStatusException(HttpStatus.CONFLICT, "Review candidates before creating a user");
        lockForCreation();
        String cleanName = nullable(name);
        String cleanStudent = nullable(studentId);
        String normalizedPhone = digits(phone);
        if (cleanName == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
        if (StringUtils.hasText(phone) && !phone.matches("[0-9 .-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid phone format");
        }
        if (StringUtils.hasText(phone) && (normalizedPhone == null || normalizedPhone.length() < 4)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone must contain at least four digits");
        }
        if (cleanStudent != null || normalizedPhone != null) {
            Integer matches = jdbc.queryForObject("""
                    select count(*) from users where
                    (? is not null and (student_id = ? or login_id = ? or email = ?)) or
                    (? is not null and replace(replace(replace(coalesce(phone, ''), '-', ''), ' ', ''), '.', '') = ?)
                    """, Integer.class, cleanStudent, cleanStudent, cleanStudent, cleanStudent, normalizedPhone, normalizedPhone);
            if (matches != null && matches > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Possible existing user; review candidates");
        }
        String base = normalizedPhone != null ? cleanName + normalizedPhone.substring(normalizedPhone.length() - 4)
                : cleanStudent != null ? cleanStudent : Long.toString(Instant.now().toEpochMilli());
        if (base.length() > 200) base = base.substring(0, 200);
        Integer occupied = jdbc.queryForObject("select count(*) from users where login_id = ? or student_id = ? or email = ?",
                Integer.class, base, base, base);
        String loginId = occupied != null && occupied > 0 ? base + "-" + UUID.randomUUID().toString().substring(0, 8) : base;
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into users (name, student_id, phone, login_id, password, status)
                    values (?, ?, ?, ?, ?, 'PENDING')
                    """, new String[]{"id"});
            statement.setString(1, cleanName);
            statement.setString(2, cleanStudent);
            statement.setString(3, nullable(phone));
            statement.setString(4, loginId);
            statement.setString(5, encoder.encode("1111"));
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    @Transactional
    public void lockForCreation() {
        jdbc.queryForObject("select id from administrative_person_lock where id = 1 for update", Integer.class);
    }

    @Transactional(readOnly = true)
    public String loginId(long userId) {
        return jdbc.queryForObject("select login_id from users where id = ?", String.class, userId);
    }

    private String nullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String digits(String value) {
        if (!StringUtils.hasText(value)) return null;
        String result = value.replaceAll("[^0-9]", "");
        return result.isEmpty() ? null : result;
    }
}
