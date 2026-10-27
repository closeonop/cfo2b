package com.empmg.platform.hr;

import com.empmg.platform.BaseIT;
import com.empmg.platform.security.AccessRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties="app.demo-otp-enabled=true")
class PortalWorkflowIT extends BaseIT {
    @Autowired JdbcTemplate db;
    record Team(Ctx admin,Ctx manager,Ctx employee,long managerId,long employeeId) {}
    Team team() throws Exception {
        var admin=onboard("Portal "+unique());
        long manager=createEmployee(admin,"Manager","Demo",uniqueEmail("mgr"),BigDecimal.ZERO,null,null).path("id").asLong();
        long employee=createEmployee(admin,"Employee","Demo",uniqueEmail("emp"),BigDecimal.ZERO,null,manager).path("id").asLong();
        return new Team(admin,createUser(admin,AccessRole.MANAGER,manager),createUser(admin,AccessRole.EMPLOYEE,employee),manager,employee);
    }
    Map<String,Object> body(Long employee,Object data) { var result=new HashMap<String,Object>();result.put("employeeId",employee);result.put("data",data);return result; }
    JsonNode record(Ctx actor,String kind,Long employee,Map<String,Object> data) throws Exception {
        var result=apiPost(actor,"/api/hr/records/"+kind,body(employee,data));assertTrue(result.has("id"),result.toString());return result;
    }
    Map<String,Object> profile(){return Map.of("firstName","Updated","lastName","Employee","phone","5551234567","address","Synthetic home address","emergencyContact","Demo Contact 5551234567","accountHolder","Updated Employee","bankName","Test Bank","accountNumber","123456789012","ifsc","TEST0123456");}

    @Test void firstLoginCannotSkipVerificationPasswordOrProfileAndPrivateFieldsStayScoped() throws Exception {
        var t=team();String email=uniqueEmail("welcome");
        var account=apiPost(t.admin,"/api/users",Map.of("email",email,"role","EMPLOYEE","employeeId",t.employeeId));
        String temporary=account.path("temporaryPassword").asText();var pending=loginAs(email,temporary);
        assertTrue(getJson(pending,"/api/auth/me").path("user").path("welcomeRequired").asBoolean());
        assertEquals(403,statusOf(pending,"GET","/api/hr/home",null));
        assertEquals(403,statusOf(pending,"POST","/api/auth/welcome/profile",profile()));
        assertEquals(403,statusOf(pending,"POST","/api/auth/welcome/password",Map.of("currentPassword",temporary,"newPassword","BetterPassword123!")));
        assertEquals(400,statusOf(pending,"POST","/api/auth/welcome/verify-code",Map.of("code","123456")));
        assertEquals(200,statusOf(pending,"POST","/api/auth/welcome/request-code",Map.of()));
        assertEquals(429,statusOf(pending,"POST","/api/auth/welcome/request-code",Map.of()));
        assertEquals(400,statusOf(pending,"POST","/api/auth/welcome/verify-code",Map.of("code","000000")));
        assertEquals(200,statusOf(pending,"POST","/api/auth/welcome/verify-code",Map.of("code","123456")));
        assertEquals(400,statusOf(pending,"POST","/api/auth/welcome/verify-code",Map.of("code","123456")));
        assertEquals(403,statusOf(pending,"POST","/api/auth/welcome/profile",profile()));
        assertEquals(200,statusOf(pending,"POST","/api/auth/welcome/password",Map.of("currentPassword",temporary,"newPassword","BetterPassword123!")));
        assertEquals(401,statusOf(pending,"GET","/api/auth/me",null));
        var changed=loginAs(email,"BetterPassword123!");
        assertEquals(403,statusOf(changed,"GET","/api/employees",null));
        var invalid=new HashMap<>(profile());invalid.put("ifsc","invalid");
        assertEquals(400,statusOf(changed,"POST","/api/auth/welcome/profile",invalid));
        assertEquals(200,statusOf(changed,"POST","/api/auth/welcome/profile",profile()));
        assertEquals(401,statusOf(changed,"GET","/api/auth/me",null));
        var ready=loginAs(email,"BetterPassword123!");assertEquals(200,statusOf(ready,"GET","/api/hr/home",null));
        assertFalse(getJson(ready,"/api/auth/me").path("user").path("welcomeRequired").asBoolean());
        assertEquals("Updated",getJson(t.admin,"/api/employees/"+t.employeeId).path("firstName").asText());
        assertEquals("•••• 9012",getJson(ready,"/api/employees/"+t.employeeId+"/private-profile").path("details").path("accountNumber").asText());
        assertEquals(403,statusOf(t.manager,"GET","/api/employees/"+t.employeeId+"/private-profile",null));
        var hr=createUser(t.admin,AccessRole.HR,null);var cfo=createUser(t.admin,AccessRole.CFO,null);
        assertFalse(getJson(hr,"/api/employees/"+t.employeeId+"/private-profile").path("details").has("accountNumber"));
        var payment=getJson(cfo,"/api/employees/"+t.employeeId+"/private-profile").path("details");
        assertEquals("123456789012",payment.path("accountNumber").asText());assertFalse(payment.has("address"));
        byte[] encrypted=db.queryForObject("select payload from employee_private_profiles where employee_id=?",byte[].class,t.employeeId);
        assertFalse(new String(encrypted,StandardCharsets.UTF_8).contains("123456789012"));
        assertFalse(getJson(t.admin,"/api/employees").toString().contains("123456789012"));
        assertEquals(404,statusOf(onboard("Other"),"GET","/api/employees/"+t.employeeId+"/private-profile",null));
    }
    @Test void codeExpiresAndFiveFailedAttemptsRemainFailedAcrossRequests() throws Exception {
        var t=team();String email=uniqueEmail("code");var account=apiPost(t.admin,"/api/users",Map.of("email",email,"role","EMPLOYEE","employeeId",t.employeeId));
        var pending=loginAs(email,account.path("temporaryPassword").asText());
        apiPost(pending,"/api/auth/welcome/request-code",Map.of());
        for(int i=0;i<5;i++) assertEquals(400,statusOf(pending,"POST","/api/auth/welcome/verify-code",Map.of("code","111111")));
        assertEquals(429,statusOf(pending,"POST","/api/auth/welcome/verify-code",Map.of("code","123456")));
        assertEquals(5,db.queryForObject("select otp_attempts from users where id=?",Integer.class,account.path("id").asLong()));
        db.update("update users set otp_attempts=0,otp_expires_at=now()-interval '1 minute' where id=?",account.path("id").asLong());
        assertEquals(400,statusOf(pending,"POST","/api/auth/welcome/verify-code",Map.of("code","123456")));
    }
    @Test void assignedManagerSeesInterviewAndCandidateButCannotRescheduleOrReadOthers() throws Exception {
        var t=team();var secondId=createEmployee(t.admin,"Other","Manager",uniqueEmail("second"),BigDecimal.ZERO,null,null).path("id").asLong();
        var second=createUser(t.admin,AccessRole.MANAGER,secondId);var cfo=createUser(t.admin,AccessRole.CFO,null);
        var position=apiPost(t.admin,"/api/job-roles",Map.of("name","Analyst"));
        var job=record(t.admin,"job-posting",null,Map.of("title","Analyst opening","positionId",position.path("id").asLong(),"description","Demo role","status","OPEN"));
        var candidate=record(t.admin,"candidate",null,Map.of("title","Demo Candidate","email",uniqueEmail("candidate"),"jobId",job.path("id").asLong(),"status","INTERVIEW"));
        var values=new HashMap<String,Object>(Map.of("title","First interview","candidateId",candidate.path("id").asLong(),"date",today().plusDays(1).toString(),"time","10:00","interviewerId",t.managerId,"status","SCHEDULED"));
        long id=record(t.admin,"interview",null,values).path("id").asLong();
        assertEquals(1,getJson(t.manager,"/api/hr/records/interview").size());assertEquals(1,getJson(t.manager,"/api/hr/records/candidate").size());
        assertEquals(0,getJson(second,"/api/hr/records/interview").size());assertEquals(0,getJson(cfo,"/api/hr/records/candidate").size());
        assertEquals(403,statusOf(second,"PUT","/api/hr/records/interview/"+id,body(null,values)));
        var reschedule=new HashMap<>(values);reschedule.put("time","11:00");assertEquals(403,statusOf(t.manager,"PUT","/api/hr/records/interview/"+id,body(null,reschedule)));
        values.put("status","COMPLETED");assertEquals(400,statusOf(t.manager,"PUT","/api/hr/records/interview/"+id,body(null,values)));
        values.put("score",4);values.put("notes","Clear synthetic evidence");assertEquals(200,statusOf(t.manager,"PUT","/api/hr/records/interview/"+id,body(null,values)));
        assertEquals(403,statusOf(t.manager,"POST","/api/hr/candidates/"+candidate.path("id").asLong()+"/convert",Map.of("startDate",today().toString())));
    }
    @Test void switchingWorkHasNoOverlapAndCompletionSyncsWithManagerWhileCfoOnlySeesTotals() throws Exception {
        var t=team();var cfo=createUser(t.admin,AccessRole.CFO,null);
        var project=record(t.manager,"project",null,Map.of("title","Release","notes","Synthetic project","dueDate",today().plusDays(5).toString(),"status","ACTIVE"));
        var task=record(t.manager,"task",t.employeeId,Map.of("title","Verify release","projectId",project.path("id").asLong(),"dueDate",today().toString(),"why","Protect quality","impact","Delay","status","TODO"));
        assertEquals(200,statusOf(t.employee,"POST","/api/attendance/clock-in",Map.of("taskId",task.path("id").asLong(),"workSummary","Private work detail")));
        assertEquals(400,statusOf(t.employee,"POST","/api/attendance/clock-in",Map.of()));
        assertEquals("IN_PROGRESS",getJson(t.manager,"/api/hr/records/task").get(0).path("data").path("status").asText());
        db.update("update attendance_entries set clock_in=clock_in-interval '2 hours' where employee_id=? and clock_out is null",t.employeeId);
        assertEquals(200,statusOf(t.employee,"POST","/api/attendance/switch-work",Map.of("workSummary","Verified the release","outcome","COMPLETED","nextSummary","Team documentation")));
        var manager=getJson(t.manager,"/api/hr/records/task");assertEquals("DONE",manager.get(0).path("data").path("status").asText());
        assertTrue(getJson(t.manager,"/api/hr/insights").path("taskHours").path(task.path("id").asText()).asDouble()>=2);
        var own=getJson(t.employee,"/api/attendance");assertEquals(2,own.path("entries").size());assertTrue(own.path("openEntry").isObject());
        var finance=getJson(cfo,"/api/attendance");assertEquals(0,finance.path("entries").size());assertTrue(finance.path("rows").size()>=2);assertFalse(finance.toString().contains("Team documentation"));
        assertEquals(0L,db.queryForObject("select count(*) from attendance_entries a join attendance_entries b on a.employee_id=b.employee_id and a.id<b.id where a.employee_id=? and a.clock_in<coalesce(b.clock_out,'infinity') and b.clock_in<coalesce(a.clock_out,'infinity')",Long.class,t.employeeId));
        assertEquals(200,statusOf(t.employee,"POST","/api/attendance/clock-out",Map.of("workSummary","Documentation drafted","outcome","IN_PROGRESS")));
        assertEquals(200,statusOf(t.employee,"POST","/api/attendance/submit",Map.of("weekStart",own.path("weekStart").asText())));
        long sheet=getJson(t.manager,"/api/attendance/timesheets").get(0).path("id").asLong();
        assertEquals(403,statusOf(cfo,"POST","/api/attendance/timesheets/"+sheet+"/approve",Map.of()));
        assertEquals(200,statusOf(t.manager,"POST","/api/attendance/timesheets/"+sheet+"/approve",Map.of()));
        assertEquals(400,statusOf(t.employee,"POST","/api/attendance/clock-in",Map.of()));
    }
    @Test void prerequisitesAndManagerOwnedChecklistCannotBeCompletedByEmployee() throws Exception {
        var t=team();var project=record(t.manager,"project",null,Map.of("title","Project","notes","Plan","dueDate",today().toString(),"status","ACTIVE"));
        var values=new HashMap<String,Object>(Map.of("title","First task","projectId",project.path("id").asLong(),"dueDate",today().toString(),"why","Needed","impact","Delay","status","TODO"));
        long first=record(t.manager,"task",t.employeeId,values).path("id").asLong();values.put("title","Second task");values.put("dependsOnId",first);
        long second=record(t.manager,"task",t.employeeId,values).path("id").asLong();
        assertEquals(400,statusOf(t.employee,"POST","/api/hr/records/task/"+second+"/complete",Map.of()));
        apiPost(t.employee,"/api/hr/records/task/"+first+"/complete",Map.of());assertEquals(200,statusOf(t.employee,"POST","/api/hr/records/task/"+second+"/complete",Map.of()));
        var checklist=record(t.manager,"onboarding",t.employeeId,Map.of("title","Manager welcome","assignedTo","MANAGER","dueDate",today().toString(),"status","TODO"));
        assertEquals(403,statusOf(t.employee,"POST","/api/hr/records/onboarding/"+checklist.path("id").asLong()+"/complete",Map.of()));
    }
    @Test void everyCatalogModuleValidatesRequiredFieldsAndTenantHomeDoesNotLeak() throws Exception {
        var t=team();
        for(var module:HrCatalog.MODULES) assertEquals(400,statusOf(t.admin,"POST","/api/hr/records/"+module.key(),body(module.employeeScoped()?t.employeeId:null,Map.of())),module.key());
        record(t.manager,"feedback",t.employeeId,Map.of("title","Private company feedback","date",today().toString(),"type","POSITIVE","notes","Synthetic comment"));
        assertFalse(getJson(onboard("Other tenant"),"/api/hr/home").toString().contains("Private company feedback"));
    }
    @Test void timeCorrectionsRequireAnotherReviewerAndRejectOverlapAndFrozenWeeks() throws Exception {
        var t=team();var start=java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        var values=Map.<String,Object>of("title","Missed check-in","clockIn",start.toString(),"clockOut",start.plusHours(2).toString(),"notes","Synthetic missed session");
        var request=record(t.employee,"attendance-request",t.employeeId,values);long id=request.path("id").asLong();
        assertEquals(403,statusOf(t.employee,"POST","/api/hr/records/attendance-request/"+id+"/approve",Map.of()));
        assertEquals(200,statusOf(t.manager,"POST","/api/hr/records/attendance-request/"+id+"/approve",Map.of()));
        var duplicate=record(t.employee,"attendance-request",t.employeeId,values);
        assertEquals(400,statusOf(t.manager,"POST","/api/hr/records/attendance-request/"+duplicate.path("id").asLong()+"/approve",Map.of()));
        long entry=db.queryForObject("select id from attendance_entries where company_id=? and employee_id=?",Long.class,t.admin.companyId(),t.employeeId);
        var edit=new HashMap<>(values);edit.put("entryId",entry);edit.put("clockOut",start.plusHours(3).toString());
        var correction=record(t.employee,"attendance-request",t.employeeId,edit);
        assertEquals(200,statusOf(t.manager,"POST","/api/hr/records/attendance-request/"+correction.path("id").asLong()+"/approve",Map.of()));
        assertEquals(3.0,getJson(t.employee,"/api/attendance?week="+start.toLocalDate()).path("rows").get(0).path("hours").asDouble());
        var monday=start.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        db.update("insert into timesheets(company_id,employee_id,week_start,status) values(?,?,?,'APPROVED')",t.admin.companyId(),t.employeeId,java.sql.Date.valueOf(monday));
        var frozen=record(t.employee,"attendance-request",t.employeeId,edit);
        assertEquals(400,statusOf(t.manager,"POST","/api/hr/records/attendance-request/"+frozen.path("id").asLong()+"/approve",Map.of()));
    }
    @Test void financeCanLinkRewardEvidenceWithoutReceivingReviewNotes() throws Exception {
        var t=team();var cfo=createUser(t.admin,AccessRole.CFO,null);
        long position=apiPost(t.admin,"/api/job-roles",Map.of("name","Tester")).path("id").asLong();
        apiPut(t.admin,"/api/employees/"+t.employeeId,Map.of("firstName","Employee","lastName","Demo","startDate","2025-01-15","jobRoleId",position,"managerId",t.managerId));
        long metric=record(t.admin,"metric",null,Map.of("title","Quality","positionId",position,"unit","Percent","target",95,"direction","HIGHER")).path("id").asLong();
        long review=record(t.manager,"review",t.employeeId,Map.of("title","Shared review","dueDate",today().toString(),"status","SHARED","scores",Map.of(String.valueOf(metric),4),"notes","Private review notes")).path("id").asLong();
        var references=getJson(cfo,"/api/hr/reward-reviews");assertEquals(1,references.size());assertFalse(references.toString().contains("Private review notes"));
        assertEquals(0,getJson(cfo,"/api/hr/records/review").size());
        var reward=record(cfo,"reward",t.employeeId,Map.of("title","Contribution","type","AWARD","reviewId",review,"date",today().toString(),"notes","Recorded by Finance"));
        assertTrue(reward.has("id"));assertEquals(1,getJson(t.employee,"/api/hr/records/reward").size());
    }
}
