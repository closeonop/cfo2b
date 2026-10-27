package com.empmg.platform.compensation;

import com.empmg.platform.BaseIT;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.security.AccessRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class PeopleFinanceIT extends BaseIT {
    @Autowired EmployeeRepo employees;

    private void annual(Ctx c) throws Exception {
        apiPut(c,"/api/company",Map.of("name","Finance QA","currency","USD","locale","en-US",
                "salaryPeriod","ANNUAL","roundingRule","NONE","hrCanSeeSalary",false));
    }
    private void amount(String expected, com.fasterxml.jackson.databind.JsonNode actual) {
        assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())));
    }

    @Test void tiersValidateAssignmentsOrderingAndArchiveRestore() throws Exception {
        var c=onboard("Bands"); var other=onboard("Other Bands");
        long t=createTier(c,"First","1000","3000").path("id").asLong();
        long second=createTier(c,"Second","3000","5000").path("id").asLong();
        long e=createEmployee(c,"Band","Person",uniqueEmail("band"),new BigDecimal("2000"),null,null).path("id").asLong();
        long foreign=createEmployee(other,"Other","Person",uniqueEmail("other"),new BigDecimal("2000"),null,null).path("id").asLong();
        assertEquals(400,statusOf(c,"POST","/api/tiers",Map.of("name"," ")));
        assertEquals(400,statusOf(c,"POST","/api/tiers",Map.of("name","Bad","minSalary",-1)));
        assertEquals(400,statusOf(c,"PUT","/api/tiers/reorder",List.of(t,t)));
        assertEquals(400,statusOf(c,"POST","/api/tiers/assign",Map.of("tierId",t,"employeeIds",List.of(e,foreign))));
        assertTrue(getJson(c,"/api/employees/"+e).path("tierId").isNull());
        var hr=createUser(c,AccessRole.HR,null);
        assertEquals(1,apiPost(hr,"/api/tiers/assign",Map.of("tierId",t,"employeeIds",List.of(e))).path("moved").asInt());
        var ordered=apiPut(c,"/api/tiers/reorder",List.of(second,t));
        assertEquals(second,ordered.path(0).path("id").asLong());
        apiPost(c,"/api/tiers/"+t+"/archive",null);
        assertEquals(400,statusOf(c,"POST","/api/tiers/assign",Map.of("tierId",t,"employeeIds",List.of(e))));
        assertEquals(t,getJson(c,"/api/employees/"+e).path("tierId").asLong());
        assertFalse(apiPost(c,"/api/tiers/"+t+"/archive",null).path("archived").asBoolean());
        assertEquals(404,statusOf(other,"PUT","/api/tiers/"+t,Map.of("name","No")));
    }

    @Test void jobRolesHaveValidatedDefaultsAndRecoverableArchives() throws Exception {
        var c=onboard("Roles QA"); var hr=createUser(c,AccessRole.HR,null);
        long t=createTier(c,"Band","1000","3000").path("id").asLong();
        assertEquals(400,statusOf(hr,"POST","/api/job-roles",Map.of("minSalary",100)));
        assertEquals(400,statusOf(hr,"POST","/api/job-roles",Map.of("name","Bad","minSalary",-1)));
        long role=apiPost(hr,"/api/job-roles",Map.of("name","Designer","defaultTierId",t,"minSalary",1000,"maxSalary",3000)).path("id").asLong();
        apiPut(hr,"/api/job-roles/"+role,Map.of("name","Product designer","defaultTierId",t));
        apiPost(hr,"/api/job-roles/"+role+"/archive",null);
        assertEquals(0,getJson(hr,"/api/job-roles").size());
        assertEquals(1,getJson(hr,"/api/job-roles?includeArchived=true").size());
        assertFalse(apiPost(hr,"/api/job-roles/"+role+"/archive",null).path("archived").asBoolean());
        apiPost(c,"/api/tiers/"+t+"/archive",null);
        assertEquals(400,statusOf(hr,"POST","/api/job-roles",Map.of("name","Archived default","defaultTierId",t)));
    }

    @Test void annualPayIsNormalizedAcrossTierReportsAndRaises() throws Exception {
        var c=onboard("Annual QA"); annual(c);
        long t=createTier(c,"Annual band","10000","30000").path("id").asLong();
        long e=createEmployee(c,"Annual","Person",uniqueEmail("annual"),new BigDecimal("12000"),t,null).path("id").asLong();
        amount("1000",getJson(c,"/api/tiers/stats").path(0).path("totalMonthlyCost"));
        amount("12000",getJson(c,"/api/tiers/stats").path(0).path("averageSalary"));
        amount("1000",getJson(c,"/api/reports/cost-by?group=tier").path("rows").path(0).path(2));
        var request=Map.of("employeeIds",List.of(e),"percent",10,"effectiveDate",today().toString(),"reason","Annual adjustment");
        amount("100",apiPost(c,"/api/raises/preview",request).path("monthlyDelta"));
        var raise=apiPost(c,"/api/raises",request);
        amount("100",raise.path("monthlyDelta"));
        amount("100",getJson(c,"/api/raises/"+raise.path("id").asLong()).path("monthlyDelta"));
        amount("13200",getJson(c,"/api/employees/"+e).path("baseSalary"));
    }

    @Test void hrCanProposeButCannotExtractSalaryFromAnyRaiseResponse() throws Exception {
        var c=onboard("Private Raise");
        long e=createEmployee(c,"Private","Person",uniqueEmail("private"),new BigDecimal("2400"),null,null).path("id").asLong();
        var hr=createUser(c,AccessRole.HR,null);
        var request=Map.of("employeeIds",List.of(e),"fixedAmount",100,"effectiveDate",today().plusDays(2).toString(),"reason","Proposed adjustment");
        var preview=apiPost(hr,"/api/raises/preview",request);
        assertTrue(preview.path("monthlyDelta").isNull());
        assertTrue(preview.path("items").path(0).path("oldSalary").isNull());
        assertTrue(preview.path("items").path(0).path("percentApplied").isNull());
        var raise=apiPost(hr,"/api/raises",request); long id=raise.path("id").asLong();
        assertEquals("PENDING",raise.path("status").asText());
        assertTrue(raise.path("totalNewMonthly").isNull());
        assertTrue(getJson(hr,"/api/raises/"+id).path("items").path(0).path("newSalary").isNull());
        amount("100",getJson(c,"/api/raises/"+id).path("monthlyDelta"));
        assertTrue(apiPost(hr,"/api/raises/"+id+"/cancel",null).path("monthlyDelta").isNull());
        var manager=createUser(c,AccessRole.MANAGER,e);
        assertEquals(403,statusOf(manager,"GET","/api/raises/"+id,null));
        assertEquals(400,statusOf(c,"POST","/api/raises/preview",Map.of("employeeIds",List.of(e,e),"percent",5,"effectiveDate",today().toString(),"reason","Duplicates")));
    }

    @Test void allReportFormatsWorkAndVariableDatesMatchRecurringCosts() throws Exception {
        var c=onboard("Reports QA");
        long e=createEmployee(c,"Report","Person",uniqueEmail("report"),new BigDecimal("2000"),null,null).path("id").asLong();
        assertEquals(400,statusOf(c,"POST","/api/employees/"+e+"/components",Map.of("name","Invalid")));
        assertEquals(400,statusOf(c,"POST","/api/employees/"+e+"/components",Map.of("name","Invalid percentage","calcType","PERCENT","value",5,"period","ONE_TIME")));
        apiPost(c,"/api/employees/"+e+"/components",Map.of("name","Commission","calcType","PERCENT","value",5,"period","MONTHLY"));
        apiPost(c,"/api/employees/"+e+"/components",Map.of("name","Future bonus","calcType","FIXED","value",500,"period","MONTHLY","startDate",today().plusDays(3).toString()));
        apiPost(c,"/api/employees/"+e+"/components",Map.of("name","Expired bonus","calcType","FIXED","value",500,"period","MONTHLY","endDate",today().minusDays(3).toString()));
        var variable=getJson(c,"/api/reports/variable-pay");
        assertEquals(1,variable.path("rows").size());
        assertEquals("PERCENT",variable.path("rows").path(0).path(2).asText());
        amount("100",variable.path("rows").path(0).path(5));
        for(String group:List.of("tier","role","team")) {
            var rows=getJson(c,"/api/reports/cost-by?group="+group).path("rows");
            amount("2100",rows.path(0).path(4));
        }
        for(String name:List.of("payroll-summary","cost-by-tier","cost-by-role","cost-by-team","salary-history","variable-pay")) {
            var response=raw(c,get("/api/reports/"+name+"/csv")).getResponse();
            assertEquals(200,response.getStatus(),name);
            assertTrue(response.getContentType().startsWith("text/csv"));
            assertFalse(response.getContentAsString().isBlank());
        }
        assertEquals(400,statusOf(c,"GET","/api/reports/salary-history?from=2026-05-01&to=2026-04-01",null));
        assertEquals(37,raw(c,get("/api/reports/payroll-summary/csv?months=1000000")).getResponse().getContentAsString().lines().count());
        var hr=createUser(c,AccessRole.HR,null);
        assertEquals(403,statusOf(hr,"GET","/api/reports/cost-by?group=team",null));
    }

    @Test void payrollSnapshotsIncludePriorEmploymentAndExcludeAfterDeparture() throws Exception {
        var c=onboard("Snapshots");
        long e=createEmployee(c,"Former","Person",uniqueEmail("former"),new BigDecimal("2000"),null,null).path("id").asLong();
        var employee=employees.findById(e).orElseThrow();
        employee.setStatus("INACTIVE");
        employee.setUpdatedAt(LocalDate.now().minusMonths(1).withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        employees.save(employee);
        var rows=getJson(c,"/api/reports/payroll-summary?months=3").path("rows");
        amount("2000",rows.path(0).path(1));
        amount("0",rows.path(1).path(1)); amount("0",rows.path(2).path(1));
    }

    @Test void bandCapsNeverReduceExistingPayOrRoundAboveTheMaximum() {
        var above=com.empmg.platform.compensation.service.MoneyMath.computeRaise(new BigDecimal("3500"),BigDecimal.TEN,null,new BigDecimal("3000"),"CAP","NONE");
        assertEquals(0,new BigDecimal("3500").compareTo(above.newSalary()));
        var rounded=com.empmg.platform.compensation.service.MoneyMath.computeRaise(new BigDecimal("1000"),new BigDecimal("5"),null,new BigDecimal("1075"),"CAP","NEAREST_100");
        assertEquals(0,new BigDecimal("1075").compareTo(rounded.newSalary()));
    }
}
