package com.empmg.platform.user;

import com.empmg.platform.common.ApiException;
import com.empmg.platform.hr.HrCatalog;
import com.empmg.platform.security.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

/** Only the current user's tutorial preferences; no account or business mutations. */
@RestController
@RequestMapping("/api/user/tours")
@RequiredArgsConstructor
public class TourController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final Set<String> PAGES = Set.of("dashboard", "employees", "employee-profile",
            "employee-import", "portal", "personal-details", "attendance", "approvals", "tiers",
            "roles", "compensation", "reports", "accounts", "audit", "settings", "my-pay");

    @GetMapping
    public List<String> list() {
        var p = SecurityUtils.current();
        String payload = jdbc.queryForObject("select tours_completed::text from users where id=? and company_id=?",
                String.class, p.userId(), p.companyId());
        try { return json.readValue(payload, new TypeReference<List<String>>() {}); }
        catch (java.io.IOException e) { throw new IllegalStateException("Invalid stored tour preferences", e); }
    }

    @PostMapping("/{tourKey}/complete")
    @Transactional
    public List<String> complete(@PathVariable String tourKey) {
        boolean known = PAGES.contains(tourKey) || HrCatalog.MODULES.stream().anyMatch(m -> ("portal-"+m.key()).equals(tourKey));
        if (!known) throw new ApiException("Unknown walkthrough.");
        var p = SecurityUtils.current();
        // Keys are an allowlisted constant. Parameters still go through a prepared statement.
        String key = "[\""+tourKey+"\"]";
        jdbc.update("update users set tours_completed = case when tours_completed @> cast(? as jsonb) " +
                "then tours_completed else tours_completed || cast(? as jsonb) end where id=? and company_id=?",
                key, key, p.userId(), p.companyId());
        return list();
    }
}
