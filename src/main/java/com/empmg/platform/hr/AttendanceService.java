package com.empmg.platform.hr;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.security.*;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.math.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AttendanceService {
    private final JdbcTemplate db;
    private final HrService hr;
    private final AuditService audit;
    public record Entry(long id,long employeeId,Long taskId,Instant clockIn,Instant clockOut,String workSummary,String outcome) {}
    public record Sheet(long id,long employeeId,LocalDate weekStart,String status,String reviewNote) {}
    public ZoneId zone(AuthPrincipal p) { return ZoneId.of(HrService.text(hr.settings(p.companyId()),"timezone")); }
    LocalDate monday(LocalDate day) { return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    private Long ownEmployee(AuthPrincipal p) {
        if(p.employeeId()==null) throw new ApiException("Ask Admin or CFO to link your account to an employee first.");
        if(!hr.employee(p,p.employeeId()).getStatus().equals("ACTIVE")) throw new ApiException("Inactive employees cannot clock in.",403);
        return p.employeeId();
    }
    private List<Entry> allEntries(AuthPrincipal p) {
        return db.query("select * from attendance_entries where company_id=? order by clock_in desc",(r,n)->new Entry(r.getLong("id"),r.getLong("employee_id"),(Long)r.getObject("task_id"),r.getTimestamp("clock_in").toInstant(),r.getTimestamp("clock_out")==null?null:r.getTimestamp("clock_out").toInstant(),r.getString("work_summary"),r.getString("outcome")),p.companyId());
    }
    public List<Entry> entries(AuthPrincipal p) {
        return allEntries(p).stream().filter(e->hr.self(p,e.employeeId())||hr.manages(p,e.employeeId())).toList();
    }
    public List<Sheet> sheets(AuthPrincipal p) {
        return db.query("select * from timesheets where company_id=? order by week_start desc,id desc",(r,n)->new Sheet(r.getLong("id"),r.getLong("employee_id"),r.getDate("week_start").toLocalDate(),r.getString("status"),r.getString("review_note")),p.companyId())
            .stream().filter(e->p.role()==AccessRole.CFO||hr.self(p,e.employeeId())||hr.manages(p,e.employeeId())).toList();
    }
    private void lockEmployee(AuthPrincipal p,long employeeId) {
        db.queryForObject("select id from employees where company_id=? and id=? for update",Long.class,p.companyId(),employeeId);
    }
    private void editable(AuthPrincipal p,long employeeId,LocalDate day) {
        boolean frozen=sheets(p).stream().anyMatch(s->s.employeeId()==employeeId&&s.weekStart().equals(monday(day))&&!s.status().equals("REJECTED"));
        if(frozen) throw new ApiException("This week has been submitted. Ask your manager to reject it before adding time.");
    }
    @Transactional
    public Object clock(AuthPrincipal p,boolean clockIn,Long taskId) { return clock(p,clockIn,taskId,"","IN_PROGRESS"); }
    @Transactional
    public Object clock(AuthPrincipal p,boolean clockIn,Long taskId,String summary,String outcome) {
        // ponytail: match HR's company->employee lock order; no overlapping intervals or task-update deadlocks.
        db.queryForObject("select id from companies where id=? for update",Long.class,p.companyId());
        long employeeId=ownEmployee(p); lockEmployee(p,employeeId);
        summary=summary==null?"":summary.trim(); outcome=outcome==null?"IN_PROGRESS":outcome;
        if(summary.length()>1000||!Set.of("IN_PROGRESS","COMPLETED","BLOCKED").contains(outcome)) throw new ApiException("Use a work note under 1,000 characters and a valid outcome.");
        var open=entries(p).stream().filter(e->e.employeeId()==employeeId&&e.clockOut()==null).findFirst();
        Instant now=Instant.now();
        if(clockIn) {
            if(open.isPresent()) throw new ApiException("You are already clocked in.");
            editable(p,employeeId,now.atZone(zone(p)).toLocalDate());
            if(taskId!=null) {
                var task=hr.get(p,"task",taskId);
                if(!Objects.equals(task.employeeId(),employeeId)||HrService.text(task.data(),"status").equals("DONE")) throw new ApiException("Choose an unfinished task assigned to you.");
                hr.action(p,"task",taskId,"start",Map.of());
            }
            db.update("insert into attendance_entries(company_id,employee_id,task_id,clock_in,work_summary) values(?,?,?,?,?)",p.companyId(),employeeId,taskId,java.sql.Timestamp.from(now),summary);
        } else {
            Entry entry=open.orElseThrow(()->new ApiException("You are not clocked in."));
            // A session may cross a week boundary: protect every week it overlaps.
            for(LocalDate day=entry.clockIn().atZone(zone(p)).toLocalDate();!day.isAfter(now.atZone(zone(p)).toLocalDate());day=day.plusWeeks(1)) editable(p,employeeId,day);
            editable(p,employeeId,now.atZone(zone(p)).toLocalDate());
            db.update("update attendance_entries set clock_out=?,work_summary=?,outcome=? where id=? and company_id=?",java.sql.Timestamp.from(now),summary.isBlank()?entry.workSummary():summary,outcome,entry.id(),p.companyId());
            if(entry.taskId()!=null&&outcome.equals("COMPLETED")) hr.action(p,"task",entry.taskId(),"complete",Map.of());
            if(entry.taskId()!=null&&outcome.equals("BLOCKED")) hr.action(p,"task",entry.taskId(),"block",Map.of());
        }
        audit.record(p,p.companyId(),clockIn?"CLOCK_IN":"CLOCK_OUT","EMPLOYEE",employeeId,Map.of());
        return Map.of("clockedIn",clockIn);
    }
    public BigDecimal hours(List<Entry> entries,long employeeId,LocalDate week,ZoneId zone) {
        Instant start=week.atStartOfDay(zone).toInstant(),end=week.plusDays(7).atStartOfDay(zone).toInstant();
        long seconds=0;
        for(Entry e:entries) if(e.employeeId()==employeeId&&e.clockOut()!=null) {
            Instant from=e.clockIn().isBefore(start)?start:e.clockIn(),to=e.clockOut().isAfter(end)?end:e.clockOut();
            if(to.isAfter(from)) seconds+=Duration.between(from,to).getSeconds();
        }
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600),2,RoundingMode.HALF_UP);
    }
    @Transactional
    public Object switchWork(AuthPrincipal p,Long nextTaskId,String summary,String outcome,String nextSummary) {
        clock(p,false,null,summary,outcome);
        clock(p,true,nextTaskId,nextSummary,"IN_PROGRESS");
        return Map.of("clockedIn",true);
    }
    public Object weekly(AuthPrincipal p,LocalDate day) {
        LocalDate week=monday(day),today=LocalDate.now(zone(p)); ZoneId zone=zone(p);
        List<Entry> entries=p.role()==AccessRole.CFO?allEntries(p):entries(p);
        List<Sheet> sheets=sheets(p); var config=hr.settings(p.companyId());
        List<Map<String,Object>> rows=new ArrayList<>(); Set<Long> ids=new LinkedHashSet<>();
        // Include visible active employees with no entries, so "not started" is visible too.
        db.queryForList("select id from employees where company_id=? and status='ACTIVE'",Long.class,p.companyId()).stream()
            .filter(id->p.role()==AccessRole.CFO||hr.self(p,id)||hr.manages(p,id)).forEach(ids::add);
        entries.forEach(e->ids.add(e.employeeId())); sheets.forEach(e->ids.add(e.employeeId()));
        for(Long id:ids) {
            BigDecimal total=hours(entries,id,week,zone);
            var firstStarts=entries.stream().filter(e->e.employeeId()==id&&monday(e.clockIn().atZone(zone).toLocalDate()).equals(week))
                .collect(java.util.stream.Collectors.toMap(e->e.clockIn().atZone(zone).toLocalDate(),Entry::clockIn,(first,second)->first.isBefore(second)?first:second));
            long late=firstStarts.values().stream().filter(start->start.atZone(zone).toLocalTime().isAfter(LocalTime.parse(HrService.text(config,"startTime")))).count();
            boolean working=entries.stream().anyMatch(e->e.employeeId()==id&&e.clockOut()==null);
            boolean started=entries.stream().anyMatch(e->e.employeeId()==id&&e.clockIn().atZone(zone).toLocalDate().equals(today));
            Map<String,Object> row=new LinkedHashMap<>(); row.put("employeeId",id); row.put("hours",total); row.put("lateStarts",late);
            row.put("shortHours",week.isBefore(monday(today))&&total.compareTo(HrService.number(config,"weeklyHours"))<0);
            row.put("presence",working?"WORKING":started?"FINISHED":"NOT_STARTED"); row.put("daysPresent",firstStarts.size());
            row.put("timesheet",sheets.stream().filter(sheet->sheet.employeeId()==id&&sheet.weekStart().equals(week)).findFirst().orElse(null)); rows.add(row);
        }
        var weekEntries=entries.stream().filter(e->{ Instant from=week.atStartOfDay(zone).toInstant(),to=week.plusDays(7).atStartOfDay(zone).toInstant();
            return e.clockIn().isBefore(to)&&(e.clockOut()==null||e.clockOut().isAfter(from));
        }).filter(e->p.role()!=AccessRole.CFO||hr.self(p,e.employeeId())).toList();
        return Map.of("weekStart",week,"today",today,"timezone",zone.getId(),"expectedHours",config.get("weeklyHours"),"rows",rows,"entries",weekEntries,
            "openEntry",entries.stream().filter(e->hr.self(p,e.employeeId())&&e.clockOut()==null).findFirst().map(e->(Object)e).orElse(""));
    }
    @Transactional
    public Object submit(AuthPrincipal p,LocalDate day) {
        long employeeId=ownEmployee(p); LocalDate week=monday(day); lockEmployee(p,employeeId);
        if(week.isAfter(monday(LocalDate.now(zone(p))))) throw new ApiException("You cannot submit a future week.");
        editable(p,employeeId,week);
        Instant end=week.plusDays(7).atStartOfDay(zone(p)).toInstant();
        if(entries(p).stream().anyMatch(e->e.employeeId()==employeeId&&e.clockOut()==null&&e.clockIn().isBefore(end))) throw new ApiException("Clock out before submitting your timesheet.");
        if(hours(entries(p),employeeId,week,zone(p)).signum()==0) throw new ApiException("There are no completed hours in this week.");
        db.update("insert into timesheets(company_id,employee_id,week_start,status) values(?,?,?,'SUBMITTED') on conflict(company_id,employee_id,week_start) do update set status='SUBMITTED',submitted_at=now(),reviewed_by=null,review_note=null",p.companyId(),employeeId,java.sql.Date.valueOf(week));
        audit.record(p,p.companyId(),"TIMESHEET_SUBMITTED","EMPLOYEE",employeeId,Map.of("week",week.toString()));
        return Map.of("status","SUBMITTED");
    }
    @Transactional
    public Object review(AuthPrincipal p,long id,boolean approve,String note) {
        Sheet sheet=sheets(p).stream().filter(s->s.id()==id).findFirst().orElseThrow(()->new ApiException("Timesheet not found.",404));
        if(!hr.manages(p,sheet.employeeId())||hr.self(p,sheet.employeeId())) throw new ApiException("Another manager or HR must approve your hours.",403);
        if(!approve&&(note==null||note.isBlank())) throw new ApiException("Explain what needs correcting.");
        if(note!=null&&note.length()>1000) throw new ApiException("Keep the note under 1,000 characters.");
        lockEmployee(p,sheet.employeeId());
        int updated=db.update("update timesheets set status=?,reviewed_by=?,review_note=? where id=? and company_id=? and status='SUBMITTED'",approve?"APPROVED":"REJECTED",p.userId(),note,id,p.companyId());
        if(updated==0) throw new ApiException("This timesheet has already been reviewed.");
        audit.record(p,p.companyId(),approve?"TIMESHEET_APPROVED":"TIMESHEET_REJECTED","TIMESHEET",id,Map.of());
        return Map.of("status",approve?"APPROVED":"REJECTED");
    }
    public String export(AuthPrincipal p,LocalDate from,LocalDate to) {
        if(!hr.hr(p)&&p.role()!=AccessRole.CFO) throw new ApiException("Only Admin, HR or CFO can export payroll hours.",403);
        if(to.isBefore(from)) throw new ApiException("Choose a valid date range.");
        // CFO gets approved hours, never HR personnel notes.
        List<Entry> entries=allEntries(p);
        try {
            StringWriter out=new StringWriter();
            try(CSVPrinter csv=new CSVPrinter(out,CSVFormat.DEFAULT)) {
                csv.printRecord("employeeId","weekStart","approvedHours");
                db.query("select * from timesheets where company_id=? and status='APPROVED' and week_start between ? and ? order by week_start",(org.springframework.jdbc.core.RowCallbackHandler)r->{
                    try { long employeeId=r.getLong("employee_id"); LocalDate week=r.getDate("week_start").toLocalDate();
                        csv.printRecord(employeeId,week,hours(entries,employeeId,week,zone(p)));
                    } catch(IOException e) { throw new IllegalStateException(e); }
                },p.companyId(),java.sql.Date.valueOf(monday(from)),java.sql.Date.valueOf(monday(to)));
            }
            audit.record(p,p.companyId(),"APPROVED_HOURS_EXPORTED","TIMESHEET",null,Map.of());
            return out.toString();
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
}
