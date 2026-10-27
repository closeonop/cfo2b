package com.empmg.platform.hr;

import com.empmg.platform.BaseIT;
import com.empmg.platform.security.AccessRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@org.springframework.test.context.TestPropertySource(properties="app.demo-otp-enabled=true")
class HrFlowIT extends BaseIT {
    @Autowired JdbcTemplate db;
    record Team(Ctx admin,Ctx manager,Ctx employee,long managerId,long employeeId) {}
    Team team() throws Exception {
        var admin=onboard("Lifecycle "+unique());
        long managerId=createEmployee(admin,"Manager","One",uniqueEmail("manager"),BigDecimal.ZERO,null,null).path("id").asLong();
        long employeeId=createEmployee(admin,"Employee","One",uniqueEmail("employee"),BigDecimal.ZERO,null,managerId).path("id").asLong();
        return new Team(admin,createUser(admin,AccessRole.MANAGER,managerId),createUser(admin,AccessRole.EMPLOYEE,employeeId),managerId,employeeId);
    }
    Map<String,Object> body(Long employeeId,Object data) { Map<String,Object> out=new HashMap<>();out.put("employeeId",employeeId);out.put("data",data);return out; }
    JsonNode record(Ctx actor,String kind,Long employeeId,Map<String,Object> data) throws Exception {
        var result=apiPost(actor,"/api/hr/records/"+kind,body(employeeId,data)); assertTrue(result.has("id"),result.toString());return result;
    }
    @Test void ownershipCompanyIsolationAndFeedbackReply() throws Exception {
        var t=team(); var other=onboard("Other");
        var feedback=record(t.manager,"feedback",t.employeeId,Map.of("title","Good delivery","type","POSITIVE","date",today().toString(),"notes","Delivered the agreed work."));
        assertEquals(1,getJson(t.employee,"/api/hr/records/feedback").size());
        long id=feedback.path("id").asLong();
        assertEquals(403,statusOf(t.employee,"PUT","/api/hr/records/feedback/"+id,body(t.employeeId,feedback.path("data"))));
        assertEquals(404,statusOf(other,"POST","/api/hr/records/feedback/"+id+"/reply",Map.of("reply","Thanks")));
        var reply=apiPost(t.employee,"/api/hr/records/feedback/"+id+"/reply",Map.of("reply","Thanks, I’ll keep it up."));
        assertEquals("Thanks, I’ll keep it up.",reply.path("data").path("reply").asText());
        assertEquals(403,statusOf(t.manager,"GET","/api/employees/"+t.employeeId+"/compensation",null));
    }
    @Test void disciplineRequiresEmployeeSideAndUsesOrderedSteps() throws Exception {
        var t=team();
        var step=record(t.admin,"discipline-step",null,Map.of("title","Written warning","order",1,"policy","Attendance policy"));
        Map<String,Object> data=new HashMap<>(Map.of("title","Attendance","stepId",step.path("id").asLong(),"date",today().toString(),"reason","Repeated late arrival","policyViolated","Start-time policy","evidence","Three dated entries","support","Adjusted transport plan"));
        assertEquals(400,statusOf(t.manager,"POST","/api/hr/records/discipline",body(t.employeeId,data)));
        data.put("employeeSide","Transport disruption explained by employee");
        var incident=record(t.manager,"discipline",t.employeeId,data);
        assertEquals(200,statusOf(t.employee,"POST","/api/hr/records/discipline/"+incident.path("id").asLong()+"/acknowledge",Map.of()));
        assertEquals(403,statusOf(t.employee,"GET","/api/hr/files?employeeId="+t.employeeId,null));
        assertTrue(getJson(t.manager,"/api/hr/insights?employeeId="+t.employeeId).has("disciplineConsistency"));
    }
    @Test void positionMetricsAndReviewsRejectUnrelatedScores() throws Exception {
        var t=team();
        var position=apiPost(t.admin,"/api/job-roles",Map.of("name","Analyst"));
        var e=getJson(t.admin,"/api/employees/"+t.employeeId);
        Map<String,Object> update=new HashMap<>(); update.put("firstName","Employee");update.put("lastName","One");update.put("startDate","2025-01-15");update.put("managerId",t.managerId);update.put("jobRoleId",position.path("id").asLong());
        apiPut(t.admin,"/api/employees/"+t.employeeId,update);
        var metric=record(t.admin,"metric",null,Map.of("title","Reports delivered","positionId",position.path("id").asLong(),"unit","reports","target",10,"direction","HIGHER"));
        long metricId=metric.path("id").asLong();
        record(t.manager,"metric-value",t.employeeId,Map.of("title","October evidence","metricId",metricId,"date",today().toString(),"value",12,"evidence","Delivery log"));
        var review=record(t.manager,"review",t.employeeId,Map.of("title","October review","dueDate",today().toString(),"status","SCHEDULED"));
        assertEquals(0,getJson(t.employee,"/api/hr/records/review").size());
        Map<String,Object> shared=Map.of("title","October review","dueDate",today().toString(),"status","SHARED","scores",Map.of(String.valueOf(metricId),4));
        assertEquals(200,statusOf(t.manager,"PUT","/api/hr/records/review/"+review.path("id").asLong(),body(t.employeeId,shared)));
        assertEquals(1,getJson(t.employee,"/api/hr/records/review").size());
        assertEquals(1,getJson(t.employee,"/api/hr/insights?employeeId="+t.employeeId).path("trend").size());
        assertEquals(404,statusOf(t.manager,"POST","/api/hr/records/review",body(t.employeeId,Map.of("title","Bad review","dueDate",today().toString(),"status","SHARED","scores",Map.of("999999",5)))));
    }
    @Test void clockTimesheetApprovalExportAndFreeze() throws Exception {
        var t=team();
        Map<String,Object> generalWork=new HashMap<>(); generalWork.put("taskId",null);
        assertEquals(400,statusOf(t.employee,"POST","/api/attendance/clock-in",Map.of("taskId","invalid")));
        assertEquals(200,statusOf(t.employee,"POST","/api/attendance/clock-in",generalWork));
        assertEquals(400,statusOf(t.employee,"POST","/api/attendance/clock-in",Map.of()));
        db.update("update attendance_entries set clock_in=clock_in-interval '2 hours' where company_id=? and employee_id=?",t.admin.companyId(),t.employeeId);
        assertEquals(200,statusOf(t.employee,"POST","/api/attendance/clock-out",Map.of()));
        var weekly=getJson(t.employee,"/api/attendance");
        assertEquals(2,weekly.path("rows").get(0).path("hours").asDouble(),0.02);
        String week=weekly.path("weekStart").asText();
        assertEquals(200,statusOf(t.employee,"POST","/api/attendance/submit",Map.of("weekStart",week)));
        long sheet=getJson(t.manager,"/api/attendance/timesheets").get(0).path("id").asLong();
        assertEquals(403,statusOf(t.employee,"POST","/api/attendance/timesheets/"+sheet+"/approve",Map.of()));
        assertEquals(200,statusOf(t.manager,"POST","/api/attendance/timesheets/"+sheet+"/approve",Map.of()));
        assertEquals(400,statusOf(t.employee,"POST","/api/attendance/clock-in",Map.of()));
        var exported=mvc.perform(withAuth(get("/api/attendance/export").param("from",week).param("to",week),t.admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(exported.contains("approvedHours")); assertTrue(exported.contains(String.valueOf(t.employeeId)));
    }
    @Test void leaveBalancesProfileRequestsAndPolicyAcknowledgements() throws Exception {
        var t=team();
        LocalDate monday=today().with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        record(t.admin,"leave-balance",t.employeeId,Map.of("title","Annual","year",monday.getYear(),"days",10));
        var leave=record(t.employee,"leave-request",t.employeeId,Map.of("title","Annual","startDate",monday.toString(),"endDate",monday.plusDays(1).toString(),"notes","Personal time"));
        long leaveId=leave.path("id").asLong();
        assertEquals(403,statusOf(t.employee,"POST","/api/hr/records/leave-request/"+leaveId+"/approve",Map.of()));
        assertEquals(200,statusOf(t.manager,"POST","/api/hr/records/leave-request/"+leaveId+"/approve",Map.of()));
        var overlap=record(t.employee,"leave-request",t.employeeId,Map.of("title","Annual","startDate",monday.toString(),"endDate",monday.toString(),"notes","Overlap"));
        assertEquals(400,statusOf(t.manager,"POST","/api/hr/records/leave-request/"+overlap.path("id").asLong()+"/approve",Map.of()));
        var profile=record(t.employee,"profile-request",t.employeeId,Map.of("title","Update phone","phone","555-0100"));
        assertEquals(400,statusOf(t.employee,"POST","/api/hr/records/profile-request",body(t.employeeId,Map.of("title","Invalid phone","phone","1".repeat(41)))));
        assertEquals(403,statusOf(t.manager,"POST","/api/hr/records/profile-request/"+profile.path("id").asLong()+"/approve",Map.of()));
        assertEquals(200,statusOf(t.admin,"POST","/api/hr/records/profile-request/"+profile.path("id").asLong()+"/approve",Map.of()));
        assertEquals("555-0100",getJson(t.employee,"/api/employees/"+t.employeeId).path("phone").asText());
        var policy=record(t.admin,"policy",null,Map.of("title","Code of conduct","notes","Respect coworkers."));
        assertEquals(200,statusOf(t.employee,"POST","/api/hr/records/policy/"+policy.path("id").asLong()+"/acknowledge",Map.of()));
    }
    @Test void hiringConversionOnlyOnceAndChecklist() throws Exception {
        var t=team();
        var position=apiPost(t.admin,"/api/job-roles",Map.of("name","Designer"));
        var job=record(t.admin,"job-posting",null,Map.of("title","Designer vacancy","positionId",position.path("id").asLong(),"description","Design products","status","OPEN"));
        var candidate=record(t.admin,"candidate",null,Map.of("title","New Person","email",uniqueEmail("hire"),"jobId",job.path("id").asLong(),"status","HIRED"));
        long id=candidate.path("id").asLong();
        assertEquals(403,statusOf(t.manager,"POST","/api/hr/candidates/"+id+"/convert",Map.of("startDate",today().toString())));
        var converted=apiPost(t.admin,"/api/hr/candidates/"+id+"/convert",Map.of("startDate",today().toString()));
        assertTrue(converted.path("data").path("employeeRecordId").asLong()>0);
        assertEquals(2,getJson(t.admin,"/api/hr/records/onboarding").size());
        assertEquals(400,statusOf(t.admin,"POST","/api/hr/candidates/"+id+"/convert",Map.of("startDate",today().toString())));
    }
    @Test void generatedCredentialsCfoCanAssignRolesAndPasswordChangeIsRequired() throws Exception {
        var t=team(); var cfo=createUser(t.admin,AccessRole.CFO,null);
        String email=uniqueEmail("generated");
        var created=apiPost(cfo,"/api/users",Map.of("email",email,"role","EMPLOYEE","employeeId",t.employeeId));
        assertTrue(created.path("temporaryPassword").asText().length()>20);
        var logged=loginAs(email,created.path("temporaryPassword").asText());assertNotNull(logged);
        assertEquals(403,statusOf(logged,"GET","/api/employees",null));
        assertEquals(403,statusOf(logged,"PUT","/api/auth/me/password",Map.of("currentPassword",created.path("temporaryPassword").asText(),"newPassword","MyNewPassword123!")));
        apiPost(logged,"/api/auth/welcome/request-code",Map.of());
        apiPost(logged,"/api/auth/welcome/verify-code",Map.of("code","123456"));
        apiPost(logged,"/api/auth/welcome/password",Map.of("currentPassword",created.path("temporaryPassword").asText(),"newPassword","MyNewPassword123!"));
        var setup=loginAs(email,"MyNewPassword123!");
        apiPost(setup,"/api/auth/welcome/profile",Map.of("firstName","Employee","lastName","One","phone","5551234567","address","Demo address","emergencyContact","Demo contact 5551234567","accountHolder","Employee One","bankName","Demo bank","accountNumber","123456789012","ifsc","TEST0123456"));
        var fresh=loginAs(email,"MyNewPassword123!");assertEquals(200,statusOf(fresh,"GET","/api/employees",null));
        assertEquals(200,statusOf(cfo,"PUT","/api/users/"+created.path("id").asLong(),Map.of("role","HR","status","ACTIVE","employeeId",t.employeeId)));
        assertEquals(200,statusOf(fresh,"GET","/api/employees/"+t.employeeId+"/compensation",null));
        assertEquals(403,statusOf(fresh,"GET","/api/employees/"+t.managerId+"/compensation",null));
        assertEquals(200,statusOf(cfo,"PUT","/api/users/"+created.path("id").asLong(),Map.of("role","HR","status","INACTIVE","employeeId",t.employeeId)));
        assertEquals(401,statusOf(fresh,"GET","/api/employees",null));
    }
    @Test void projectsTasksGoalsAndHelpDesk() throws Exception {
        var t=team();
        var project=record(t.manager,"project",null,Map.of("title","Launch","notes","Ship release","dueDate",today().plusDays(7).toString(),"status","ACTIVE"));
        var task=record(t.manager,"task",t.employeeId,Map.of("title","Check release","projectId",project.path("id").asLong(),"dueDate",today().toString(),"why","Prevent regressions","impact","Release delay","status","TODO"));
        assertEquals(200,statusOf(t.employee,"POST","/api/hr/records/task/"+task.path("id").asLong()+"/complete",Map.of()));
        var goal=record(t.employee,"goal",t.employeeId,Map.of("title","Testing skill","dueDate",today().plusMonths(1).toString(),"progress",25,"notes","Own release checks"));
        record(t.employee,"training",t.employeeId,Map.of("title","Mentoring","goalId",goal.path("id").asLong(),"type","MENTORING","status","PLANNED"));
        record(t.employee,"skill",t.employeeId,Map.of("title","Testing","level","WORKING"));
        var help=record(t.employee,"help-request",t.employeeId,Map.of("title","Policy question","category","OTHER","notes","Which policy applies?"));
        assertEquals(200,statusOf(t.admin,"POST","/api/hr/records/help-request/"+help.path("id").asLong()+"/resolve",Map.of("note","See the company code of conduct.")));
        assertEquals(400,statusOf(t.employee,"POST","/api/hr/records/goal",body(t.employeeId,Map.of("title","Invalid","dueDate",today().toString(),"progress",101,"notes","Impossible"))));
    }
    @Test void privateFilesEncryptedAndSignedDownloadsAudited() throws Exception {
        var t=team();
        byte[] content="Confidential employee evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file=new org.springframework.mock.web.MockMultipartFile("file","evidence.txt","text/plain",content);
        mvc.perform(withAuth(multipart("/api/hr/files").file(file).param("employeeId",String.valueOf(t.employeeId)),t.employee)).andExpect(status().isForbidden());
        var uploaded=mvc.perform(withAuth(multipart("/api/hr/files").file(file).param("employeeId",String.valueOf(t.employeeId)),t.manager)).andExpect(status().isOk()).andReturn();
        long id=json.readTree(uploaded.getResponse().getContentAsString()).path("id").asLong();
        var link=apiPost(t.manager,"/api/hr/files/"+id+"/link",Map.of());
        var download=mvc.perform(get("/api"+link.path("path").asText())).andExpect(status().isOk()).andReturn();
        assertArrayEquals(content,download.getResponse().getContentAsByteArray());
        assertEquals(403,statusOf(t.employee,"POST","/api/hr/files/"+id+"/link",Map.of()));
        byte[] stored=db.queryForObject("select payload from hr_records where id=?",byte[].class,id);
        assertFalse(new String(stored,java.nio.charset.StandardCharsets.UTF_8).contains("evidence.txt"));
        Long audited=db.queryForObject("select count(*) from audit_log where company_id=? and action='PERSONNEL_FILE_DOWNLOADED'",Long.class,t.admin.companyId());
        assertTrue(audited>0);
    }
}
