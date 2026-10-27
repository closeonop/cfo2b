package com.empmg.platform.hr;
import com.empmg.platform.security.AuthPrincipal;
import com.empmg.platform.user.repo.UserRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledHrJobs {
    private final HrService hr;
    private final UserRepo users;
    private final HrCrypto crypto;
    private final ObjectMapper json;
    private final JdbcTemplate db;
    // Refresh in-app reminders hourly. The live inbox also evaluates them immediately.
    @Scheduled(cron="0 15 * * * *")
    public void refreshReminders() {
        for(var user:users.findAll()) if(user.getStatus().equals("ACTIVE")&&!user.isPasswordChangeRequired()) {
            try {
                var actor=new AuthPrincipal(user.getId(),user.getCompanyId(),user.getEmail(),user.getAccessRole(),user.getEmployeeId());
                byte[] payload=crypto.encrypt(json.writeValueAsBytes(hr.reminders(actor)));
                db.update("insert into hr_notifications(user_id,company_id,payload) values(?,?,?) on conflict(user_id) do update set payload=excluded.payload,updated_at=now()",user.getId(),user.getCompanyId(),payload);
            } catch(Exception ex) { log.warn("Could not refresh HR reminders for account {}",user.getId()); }
        }
    }
}
