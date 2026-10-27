package com.empmg.platform.auth.service;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.hr.HrCrypto;
import com.empmg.platform.security.*;
import com.empmg.platform.user.domain.User;
import com.empmg.platform.user.repo.UserRepo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WelcomeService {
    private final JdbcTemplate db;
    private final UserRepo users;
    private final EmployeeRepo employees;
    private final HrCrypto crypto;
    private final ObjectMapper json;
    private final AuditService audit;
    private final SensitiveRateLimiter limiter;
    private final com.empmg.platform.auth.repo.RefreshTokenRepo refreshTokens;
    @Value("${app.demo-otp-enabled:false}") private boolean demoOtp;

    public User pending(AuthPrincipal p) {
        User user=users.findById(p.userId()).filter(u->u.getCompanyId().equals(p.companyId())&&u.isWelcomeRequired())
            .orElseThrow(()->new ApiException("Account setup is not pending.",403));
        if(user.getEmployeeId()==null) throw new ApiException("Ask Admin to link your employee record.");
        return user;
    }
    private void lock(AuthPrincipal p) { db.queryForObject("select id from users where id=? and company_id=? for update",Long.class,p.userId(),p.companyId()); }
    public void requireVerified(AuthPrincipal p) {
        lock(p); pending(p);
        if(!demoOtp) throw new ApiException("Email verification delivery is not configured.",503);
        boolean verified=Boolean.TRUE.equals(db.queryForObject("select email_verified_at is not null from users where id=?",Boolean.class,p.userId()));
        if(!verified) throw new ApiException("Verify your sign-in email first.",403);
    }
    public Map<String,Object> status(AuthPrincipal p) {
        User user=pending(p);
        var employee=employees.findByIdAndCompanyId(user.getEmployeeId(),p.companyId()).orElseThrow();
        boolean verified=Boolean.TRUE.equals(db.queryForObject("select email_verified_at is not null from users where id=?",Boolean.class,p.userId()));
        return Map.of("email",user.getEmail(),"verified",verified,"passwordRequired",user.isPasswordChangeRequired(),"demoMode",demoOtp,
            "firstName",employee.getFirstName(),"lastName",employee.getLastName(),"phone",Objects.toString(employee.getPhone(),""));
    }
    @Transactional
    public Object requestCode(AuthPrincipal p) {
        lock(p); pending(p);
        if(!demoOtp) throw new ApiException("Email verification delivery is not configured. Ask your administrator.",503);
        if(!limiter.allow("welcome-send:"+p.userId(),5)) throw new ApiException("Too many code requests. Try again in 15 minutes.",429);
        boolean recent=Boolean.TRUE.equals(db.queryForObject("select otp_requested_at > now()-interval '60 seconds' from users where id=?",Boolean.class,p.userId()));
        if(recent) throw new ApiException("Wait 60 seconds before requesting another code.",429);
        db.update("update users set otp_requested_at=now(),otp_expires_at=now()+interval '10 minutes',otp_attempts=0 where id=?",p.userId());
        audit.record(p,p.companyId(),"DEMO_VERIFICATION_REQUESTED","USER",p.userId(),Map.of());
        return Map.of("message","Demo verification is enabled. Use 123456; no email is sent.","expiresIn",600,"resendAfter",60);
    }
    @Transactional(noRollbackFor=ApiException.class)
    public Object verify(AuthPrincipal p,String code) {
        lock(p); pending(p);
        if(!demoOtp) throw new ApiException("Email verification delivery is not configured.",503);
        var challenge=db.queryForMap("select otp_expires_at > now() as valid,otp_attempts,email_verified_at from users where id=?",p.userId());
        if(challenge.get("email_verified_at")!=null) throw new ApiException("This code has already been used.");
        if(!Boolean.TRUE.equals(challenge.get("valid"))) throw new ApiException("Request a new code. Codes expire after 10 minutes.");
        if(((Number)challenge.get("otp_attempts")).intValue()>=5) throw new ApiException("Too many attempts. Request a new code after the cooldown.",429);
        db.update("update users set otp_attempts=otp_attempts+1 where id=?",p.userId());
        if(!"123456".equals(code)) throw new ApiException("The verification code is incorrect.");
        db.update("update users set email_verified_at=now(),otp_expires_at=null where id=?",p.userId());
        audit.record(p,p.companyId(),"DEMO_EMAIL_VERIFIED","USER",p.userId(),Map.of());
        return Map.of("verified",true,"demoMode",true);
    }
    public record ProfileInput(
        @NotBlank @Size(max=100) String firstName, @NotBlank @Size(max=100) String lastName,
        @NotBlank @Pattern(regexp="[+0-9 ()-]{7,40}") String phone,
        @NotBlank @Size(max=500) String address,
        @NotBlank @Size(max=160) String emergencyContact,
        @NotBlank @Size(max=100) String accountHolder,
        @NotBlank @Size(max=100) String bankName,
        @NotBlank @Pattern(regexp="[0-9]{6,34}") String accountNumber,
        @NotBlank @Pattern(regexp="[A-Z]{4}0[A-Z0-9]{6}") String ifsc) {}
    @Transactional
    public Object complete(AuthPrincipal p,ProfileInput input) {
        lock(p); User user=pending(p); requireVerified(p);
        if(user.isPasswordChangeRequired()) throw new ApiException("Set your own password before saving your profile.",403);
        var employee=employees.findByIdAndCompanyId(user.getEmployeeId(),p.companyId()).orElseThrow();
        if(!employee.getStatus().equals("ACTIVE")) throw new ApiException("Your employee record is inactive.",403);
        try {
            byte[] payload=crypto.encrypt(json.writeValueAsBytes(input));
            db.update("insert into employee_private_profiles(employee_id,company_id,payload) values(?,?,?) on conflict(employee_id) do update set payload=excluded.payload,updated_at=now()",employee.getId(),p.companyId(),payload);
        } catch(java.io.IOException e) { throw new IllegalStateException("Could not save profile",e); }
        employee.setFirstName(input.firstName().trim()); employee.setLastName(input.lastName().trim()); employee.setPhone(input.phone().trim());
        employee.setUpdatedAt(Instant.now()); employees.save(employee);
        user.setWelcomeRequired(false); user.setCredentialVersion(user.getCredentialVersion()+1); users.save(user);
        refreshTokens.revokeAllForUser(user.getId());
        audit.record(p,p.companyId(),"EMPLOYEE_SETUP_COMPLETED","EMPLOYEE",employee.getId(),Map.of());
        return Map.of("completed",true);
    }
    public Object privateProfile(AuthPrincipal p,long employeeId) {
        employees.findByIdAndCompanyId(employeeId,p.companyId()).orElseThrow(()->new ApiException("Employee not found.",404));
        boolean self=Objects.equals(p.employeeId(),employeeId);
        if(!self&&!Set.of(AccessRole.ADMIN,AccessRole.HR,AccessRole.CFO).contains(p.role())) throw new ApiException("You cannot open this profile.",403);
        var payloads=db.query("select payload from employee_private_profiles where company_id=? and employee_id=?",(r,n)->r.getBytes(1),p.companyId(),employeeId);
        if(payloads.isEmpty()) return Map.of("completed",false);
        try {
            Map<String,Object> data=json.readValue(crypto.decrypt(payloads.getFirst()),new TypeReference<LinkedHashMap<String,Object>>(){});
            if(p.role()==AccessRole.CFO&&!self) data.keySet().retainAll(Set.of("accountHolder","bankName","accountNumber","ifsc"));
            else if(p.role()==AccessRole.HR&&!self) for(String key:List.of("accountHolder","bankName","accountNumber","ifsc")) data.remove(key);
            if(self&&data.get("accountNumber") instanceof String number) data.put("accountNumber","•••• "+number.substring(number.length()-4));
            audit.record(p,p.companyId(),"PRIVATE_PROFILE_VIEWED","EMPLOYEE",employeeId,Map.of());
            return Map.of("completed",true,"details",data);
        } catch(java.io.IOException e) { throw new IllegalStateException("Could not open profile",e); }
    }
}
