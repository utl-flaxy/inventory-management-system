package com.example.demo.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JwtUserAuthenticator {
    private final JdbcTemplate jdbc;

    // No EntityManager cache, business snapshot, password projection or replica read.
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public AuthPrincipal authenticate(JwtUtil.VerifiedJwt token) {
        var rows = jdbc.query("SELECT id,auth_uid,token_version,role FROM users WHERE auth_uid = ?",
                (rs, row) -> new AuthPrincipal(rs.getLong("id"), rs.getString("auth_uid"),
                        rs.getLong("token_version"), rs.getString("role")), token.uid());
        if (rows.size() != 1 || rows.get(0).tokenVersion() != token.tokenVersion())
            throw new UsernameNotFoundException("Authentication state is no longer valid");
        return rows.get(0);
    }
}
