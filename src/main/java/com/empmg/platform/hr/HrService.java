package com.empmg.platform.hr;

import com.empmg.platform.audit.service.AuditService;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.common.CsvSafety;
import com.empmg.platform.employee.domain.Employee;
import com.empmg.platform.employee.repo.EmployeeRepo;
import com.empmg.platform.employee.repo.JobRoleRepo;
import com.empmg.platform.employee.service.EmployeeService;
import com.empmg.platform.security.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class HrService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final HrCrypto crypto;
    private final EmployeeRepo employees;
    private final JobRoleRepo positions;
    private final EmployeeService employeeService;
    private final AuditService audit;
    public record Record(long id,String kind,Long employeeId,long ownerId,Map<String,Object> data,Instant createdAt,Instant updatedAt) {}
    public boolean hr(AuthPrincipal p) { return p.role()==AccessRole.ADMIN || p.role()==AccessRole.HR; }
    public Employee employee(AuthPrincipal p,Long id) {
        if(id==null) throw new ApiException("Choose an employee.");
        return employees.findByIdAndCompanyId(id,p.companyId()).orElseThrow(() -> new ApiException("Employee not found.",404));
    }
    public boolean manages(AuthPrincipal p, Long id) {
        if(hr(p)) return true;
        Employee e = employee(p,id);
        return p.role()==AccessRole.MANAGER && p.employeeId()!=null && p.employeeId().equals(e.getManagerId());
    }
    public boolean self(AuthPrincipal p,Long id) { return id!=null && id.equals(p.employeeId()); }
    boolean readable(AuthPrincipal p,Record r) {
        if(r.kind().equals("document")) return manages(p,r.employeeId());
        var m=HrCatalog.get(r.kind());
        if(m.access().equals("hiring")) {
            if(hr(p)) return true;
            if(p.role()!=AccessRole.MANAGER||p.employeeId()==null) return false;
            var assigned=all(p.companyId(),"interview").stream().filter(i->Objects.equals(id(i.data(),"interviewerId"),p.employeeId())).toList();
            if(r.kind().equals("interview")) return assigned.stream().anyMatch(i->i.id()==r.id());
            if(r.kind().equals("candidate")) return assigned.stream().anyMatch(i->Objects.equals(id(i.data(),"candidateId"),r.id()));
            return all(p.companyId(),"candidate").stream().filter(c->Objects.equals(id(c.data(),"jobId"),r.id()))
                .anyMatch(c->assigned.stream().anyMatch(i->Objects.equals(id(i.data(),"candidateId"),c.id())));
        }
        if(m.access().equals("hr")) return hr(p);
        if(r.kind().equals("reward")&&p.role()==AccessRole.CFO) return true;
        if(m.employeeScoped()) {
            if(hr(p)||manages(p,r.employeeId())) return true;
            if(!self(p,r.employeeId())) return false;
            // Employee self-service is distinct from opening the confidential personnel file.
            return !r.kind().equals("review") || text(r.data(),"status").equals("SHARED");
        }
        return true;
    }
    private void canWrite(AuthPrincipal p,String kind,Long employeeId) {
        var m=HrCatalog.get(kind);
        if(kind.equals("leave-balance")&&!hr(p)) throw new ApiException("Only HR or Admin can set leave allowances.",403);
        if(m.employeeScoped()) {
            employee(p,employeeId);
            if(manages(p,employeeId)) return;
            if(Set.of("self","request").contains(m.access()) && self(p,employeeId)) return;
            if(m.access().equals("reward") && p.role()==AccessRole.CFO) return;
        } else {
            if(Set.of("hr","hiring").contains(m.access()) && hr(p)) return;
            if(kind.equals("interview")&&p.role()==AccessRole.MANAGER) return;
            if(m.access().equals("project") && (hr(p)||p.role()==AccessRole.MANAGER)) return;
            if(m.access().equals("definition") && (p.role()==AccessRole.ADMIN
                    || hr(p) && kind.equals("policy"))) return;
        }
        throw new ApiException("You don't have permission to change this record.",403);
    }
    // ponytail: at launch, bounded to a few hundred employees. Move filtering to SQL as companies grow.
    public List<Record> all(Long companyId,String kind) {
        return db.query("select * from hr_records where company_id=? and kind=? order by id desc",(rs,n)-> {
            try { return new Record(rs.getLong("id"),rs.getString("kind"),(Long)rs.getObject("employee_id"),rs.getLong("owner_id"),
                json.readValue(crypto.decrypt(rs.getBytes("payload")),new TypeReference<Map<String,Object>>(){}),
                rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
            } catch(IOException ex) { throw new IllegalStateException("Invalid HR record",ex); }
        },companyId,kind);
    }
    public Record get(AuthPrincipal p,String kind,long id) {
        Record r=all(p.companyId(),kind).stream().filter(x->x.id()==id).findFirst()
                .orElseThrow(()->new ApiException("Record not found.",404));
        if(!readable(p,r)) throw new ApiException("You don't have access to this record.",403);
        audit.record(p,p.companyId(),"HR_VIEWED",kind,id,Map.of());
        return r;
    }
    public List<Record> list(AuthPrincipal p,String kind,Long employeeId) {
        HrCatalog.get(kind);
        List<Record> records=all(p.companyId(),kind).stream().filter(r->employeeId==null||employeeId.equals(r.employeeId()))
                .filter(r->readable(p,r)).toList();
        records.forEach(r->audit.record(p,p.companyId(),"HR_VIEWED",kind,r.id(),Map.of()));
        return records;
    }
    @Transactional
    public Record save(AuthPrincipal p,String kind,Long id,Long employeeId,Map<String,Object> input) {
        canWrite(p,kind,employeeId);
        db.queryForObject("select id from companies where id=? for update",Long.class,p.companyId());
        Record old=id==null?null:get(p,kind,id);
        if(old!=null) {
            canWrite(p,kind,old.employeeId());
            if(!Objects.equals(employeeId,old.employeeId())) throw new ApiException("The employee cannot be changed on an existing record.");
            if(Set.of("request").contains(HrCatalog.get(kind).access()) && !text(old.data(),"status").equals("PENDING"))
                throw new ApiException("A reviewed request cannot be edited.");
        }
        Map<String,Object> data=validate(p,kind,employeeId,input);
        if(kind.equals("interview")) {
            if(!hr(p)) {
                if(old==null||!Objects.equals(id(old.data(),"interviewerId"),p.employeeId())) throw new ApiException("Only the assigned interviewer can update this interview.",403);
                for(String key:List.of("candidateId","interviewerId","date","time")) if(!text(data,key).equals(text(old.data(),key)))
                    throw new ApiException("Ask HR to change the interview assignment or schedule.",403);
            }
            data.putIfAbsent("status","DRAFT");
            if(text(data,"status").equals("SCHEDULED")&&(id(data,"interviewerId")==null||text(data,"time").isBlank()))
                throw new ApiException("Choose an interviewer and start time before scheduling.");
            if(text(data,"status").equals("COMPLETED")&&(!data.containsKey("score")||text(data,"notes").isBlank()))
                throw new ApiException("Add a score and interview notes before marking complete.");
        }
        if(old!=null) for(String key:List.of("acknowledgedAt","reply","status","reviewNote","employeeRecordId","acknowledgements","completedAt")) {
            if(kind.equals("policy")&&key.equals("acknowledgements")&&!Objects.equals(old.data().get("notes"),data.get("notes"))) continue;
            if(old.data().containsKey(key) && !data.containsKey(key)) data.put(key,old.data().get(key));
        }
        if(HrCatalog.get(kind).access().equals("request")) data.put("status","PENDING");
        if(kind.equals("discipline-step")) {
            if(all(p.companyId(),kind).stream().anyMatch(r->!Objects.equals(id,r.id()) && number(r.data(),"order").compareTo(number(data,"order"))==0))
                throw new ApiException("Each discipline step needs a different order.");
        }
        if(kind.equals("hr-settings") && id==null && !all(p.companyId(),kind).isEmpty()) throw new ApiException("Edit the existing HR settings.");
        if(kind.equals("task")&&id!=null&&Objects.equals(id,HrService.id(data,"dependsOnId"))) throw new ApiException("A task cannot depend on itself.");
        if(kind.equals("task")) {
            Set<Long> seen=new HashSet<>(); Long dependency=HrService.id(data,"dependsOnId");
            while(dependency!=null) {
                if(Objects.equals(id,dependency)||!seen.add(dependency)) throw new ApiException("This dependency would create a task loop.");
                dependency=HrService.id(get(p,"task",dependency).data(),"dependsOnId");
            }
            if(text(data,"status").equals("DONE")&&!data.containsKey("completedAt")) data.put("completedAt",Instant.now().toString());
            if(text(data,"status").equals("DONE")) checkTaskDependency(p,data);
            if(!text(data,"status").equals("DONE")) data.remove("completedAt");
        }
        if(kind.equals("leave-balance") && all(p.companyId(),kind).stream().anyMatch(r->!Objects.equals(id,r.id())
                && employeeId.equals(r.employeeId()) && text(r.data(),"title").equals(text(data,"title")) && text(r.data(),"year").equals(text(data,"year"))))
            throw new ApiException("Edit the existing balance for this leave type and year.");
        return store(p,kind,id,employeeId,data);
    }
    Record store(AuthPrincipal p,String kind,Long id,Long employeeId,Map<String,Object> data) {
        try {
            byte[] bytes=crypto.encrypt(json.writeValueAsBytes(data));
            if(id==null) id=db.queryForObject("insert into hr_records(company_id,kind,employee_id,owner_id,payload) values(?,?,?,?,?) returning id",Long.class,
                    p.companyId(),kind,employeeId,p.userId(),bytes);
            else db.update("update hr_records set payload=?,updated_at=now() where id=? and company_id=? and kind=?",bytes,id,p.companyId(),kind);
            audit.record(p,p.companyId(),"HR_SAVED",kind,id,Map.of());
            long recordId=id;
            return all(p.companyId(),kind).stream().filter(r->r.id()==recordId).findFirst().orElseThrow();
        } catch(IOException ex) { throw new IllegalStateException(ex); }
    }
    Map<String,Object> validate(AuthPrincipal p,String kind,Long employeeId,Map<String,Object> input) {
        Map<String,Object> data=new LinkedHashMap<>();
        var module=HrCatalog.get(kind);
        for(var field:module.fields()) {
            Object value=input.get(field.key());
            if(value==null||String.valueOf(value).isBlank()) {
                if(field.required()) throw new ApiException(field.label()+" is required.");
                continue;
            }
            String s=String.valueOf(value).trim();
            if(s.length()>12000) throw new ApiException(field.label()+" is too long.");
            if(field.key().equals("phone")&&s.length()>40 || field.type().equals("email")&&s.length()>255)
                throw new ApiException(field.label()+" is too long.");
            try {
                switch(field.type()) {
                    case "date" -> { LocalDate.parse(s); data.put(field.key(),s); }
                    case "datetime" -> { LocalDateTime.parse(s); data.put(field.key(),s); }
                    case "time" -> { LocalTime.parse(s); data.put(field.key(),s); }
                    case "select" -> {
                        if(!field.options().contains(s)) throw new ApiException("Choose a valid "+field.label()+".");
                        data.put(field.key(),s);
                    }
                    case "number","positive","score","progress" -> {
                        BigDecimal n=new BigDecimal(s);
                        if(n.abs().compareTo(new BigDecimal("1000000000"))>0 || n.scale()>6
                            || field.type().equals("positive")&&n.signum()<=0
                            || field.type().equals("score")&&(n.signum()<0||n.compareTo(new BigDecimal("5"))>0)
                            || field.type().equals("progress")&&(n.signum()<0||n.compareTo(new BigDecimal("100"))>0))
                            throw new ApiException("Enter a valid "+field.label()+".");
                        data.put(field.key(),n);
                    }
                    case "reference" -> {
                        long refId=Long.parseLong(s);
                        if(field.reference().equals("employee")) {
                            Employee interviewer=employee(p,refId);
                            if(!interviewer.getStatus().equals("ACTIVE")) throw new ApiException("Choose an active interviewer.");
                            boolean eligible=usersForInterview(p,refId);
                            if(!eligible) throw new ApiException("The interviewer needs an active Manager, HR or Admin account.");
                        } else if(field.reference().equals("position")) {
                            positions.findByIdAndCompanyId(refId,p.companyId()).orElseThrow(()->new ApiException("Position not found."));
                        } else {
                            Record linked=kind.equals("reward")&&field.key().equals("reviewId")&&p.role()==AccessRole.CFO
                                ?rewardReviews(p).stream().filter(r->r.id()==refId).findFirst().orElseThrow(()->new ApiException("Choose a shared review for this employee."))
                                :get(p,field.reference(),refId);
                            if(linked.employeeId()!=null && !linked.employeeId().equals(employeeId) && !field.key().equals("dependsOnId"))
                                throw new ApiException("The linked record belongs to another employee.");
                        }
                        data.put(field.key(),refId);
                    }
                    case "scores" -> {
                        Map<?,?> scores=value instanceof Map<?,?> map?map:json.readValue(s,Map.class);
                        Map<String,BigDecimal> checked=new LinkedHashMap<>();
                        for(var entry:scores.entrySet()) {
                            Record metric=get(p,"metric",Long.parseLong(entry.getKey().toString()));
                            checkPosition(p,employeeId,metric);
                            BigDecimal score=new BigDecimal(entry.getValue().toString());
                            if(score.signum()<0||score.compareTo(BigDecimal.valueOf(5))>0) throw new ApiException("Review scores must be 0–5.");
                            checked.put(String.valueOf(metric.id()),score);
                        }
                        data.put(field.key(),checked);
                    }
                    case "email" -> {
                        if(!s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw new ApiException("Enter a valid email.");
                        data.put(field.key(),s.toLowerCase(Locale.ROOT));
                    }
                    default -> data.put(field.key(),s);
                }
            } catch(ApiException ex) { throw ex; }
            catch(Exception ex) { throw new ApiException("Enter a valid "+field.label()+"."); }
        }
        if(kind.equals("hr-settings")) {
            try { ZoneId.of(text(data,"timezone")); } catch(Exception ex) { throw new ApiException("Enter a time zone such as Asia/Kolkata."); }
            if(number(data,"weeklyHours").compareTo(BigDecimal.valueOf(168))>0) throw new ApiException("A week has at most 168 hours.");
        }
        if(kind.equals("metric-value")) checkPosition(p,employeeId,get(p,"metric",id(data,"metricId")));
        if(kind.equals("review")&&text(data,"status").equals("SHARED")) {
            Employee employee=employee(p,employeeId);
            Set<String> expected=new HashSet<>();
            all(p.companyId(),"metric").stream().filter(r->Objects.equals(id(r.data(),"positionId"),employee.getJobRoleId())).forEach(r->expected.add(String.valueOf(r.id())));
            if(expected.isEmpty()||!(data.get("scores") instanceof Map<?,?> scores)||!scores.keySet().equals(expected))
                throw new ApiException("Score every metric for this position before sharing the review.");
        }
        if(kind.equals("leave-request")) {
            LocalDate start=LocalDate.parse(text(data,"startDate")),end=LocalDate.parse(text(data,"endDate"));
            if(end.isBefore(start)||start.getYear()!=end.getYear()||end.isAfter(start.plusDays(366))) throw new ApiException("Choose a valid date range within one year.");
            data.put("days",workingDays(start,end));
            if(((int)data.get("days"))==0) throw new ApiException("Choose at least one working day.");
        }
        if(kind.equals("attendance-request")) correctionInterval(p,data);
        if(kind.equals("profile-request")&&!data.containsKey("phone")&&!data.containsKey("email")) throw new ApiException("Enter a new phone or email.");
        return data;
    }
    private void checkPosition(AuthPrincipal p,Long employeeId,Record metric) {
        if(!Objects.equals(id(metric.data(),"positionId"),employee(p,employeeId).getJobRoleId()))
            throw new ApiException("Use a metric defined for this employee’s position.");
    }
    public Map<String,Object> settings(Long companyId) {
        return all(companyId,"hr-settings").stream().findFirst().map(Record::data)
                .orElse(Map.of("timezone","Asia/Kolkata","weeklyHours",40,"startTime","09:00","feedbackDays",14,"retentionDays",2555));
    }
    @Transactional
    public Record action(AuthPrincipal p,String kind,long recordId,String action,Map<String,Object> body) {
        // ponytail: serialize company writes at launch; use per-record locks at larger volumes.
        db.queryForObject("select id from companies where id=? for update",Long.class,p.companyId());
        db.queryForList("select id from hr_records where id=? and company_id=? and kind=? for update",recordId,p.companyId(),kind);
        Record r=get(p,kind,recordId);
        Map<String,Object> data=new LinkedHashMap<>(r.data());
        if(action.equals("acknowledge") || action.equals("reply")) {
            if(kind.equals("policy")) {
                if(p.employeeId()==null) throw new ApiException("Link your account to an employee to acknowledge a policy.");
                Map<String,Object> acks=new LinkedHashMap<>();
                if(data.get("acknowledgements") instanceof Map<?,?> old) old.forEach((k,v)->acks.put(k.toString(),v));
                acks.put(String.valueOf(p.employeeId()),Instant.now().toString()); data.put("acknowledgements",acks);
            } else {
                if(!self(p,r.employeeId()) || !Set.of("feedback","meeting","discipline","review").contains(kind)) throw new ApiException("Only the employee can reply or acknowledge.",403);
                data.put("acknowledgedAt",Instant.now().toString());
                if(action.equals("reply")) {
                    String reply=text(body,"reply");
                    if(reply.isBlank()||reply.length()>4000) throw new ApiException("Enter a reply of at most 4,000 characters.");
                    data.put("reply",reply);
                }
            }
        } else if(action.equals("complete")) {
            boolean assigned=self(p,r.employeeId());
            if(kind.equals("onboarding")&&text(data,"assignedTo").equals("MANAGER")) assigned=manages(p,r.employeeId());
            if(!Set.of("task","onboarding").contains(kind)||!(assigned||manages(p,r.employeeId()))) throw new ApiException("You cannot complete this item.",403);
            if(kind.equals("task")) checkTaskDependency(p,data);
            data.put("status","DONE"); data.put("completedAt",Instant.now().toString());
        } else if(action.equals("start")||action.equals("block")) {
            if(!kind.equals("task")||!(self(p,r.employeeId())||manages(p,r.employeeId()))) throw new ApiException("You cannot update this task.",403);
            if(text(data,"status").equals("DONE")) throw new ApiException("This task is already completed.");
            data.put("status",action.equals("start")?"IN_PROGRESS":"BLOCKED");
        } else if(action.equals("approve")||action.equals("reject")||action.equals("resolve")) {
            if(action.equals("resolve")&&!kind.equals("help-request")) throw new ApiException("Only help requests can be resolved.");
            if(!HrCatalog.get(kind).access().equals("request")) throw new ApiException("This record does not need approval.");
            if(!manages(p,r.employeeId())||self(p,r.employeeId())) throw new ApiException("Another manager or HR must review this request.",403);
            if(Set.of("profile-request","help-request").contains(kind)&&!hr(p)) throw new ApiException("HR reviews these requests.",403);
            if(!text(data,"status").equals("PENDING")) throw new ApiException("This request has already been reviewed.");
            if(kind.equals("leave-request")&&action.equals("approve")) approveLeave(p,r);
            if(kind.equals("profile-request")&&action.equals("approve")) applyProfile(p,r);
            if(kind.equals("attendance-request")&&action.equals("approve")) applyTimeCorrection(p,r,data);
            String note=text(body,"note");
            if(note.length()>4000) throw new ApiException("Reply is too long.");
            if((action.equals("reject")||kind.equals("help-request"))&&note.isBlank()) throw new ApiException("Add a reply explaining the decision.");
            data.put("status",action.equals("reject")?"REJECTED":action.equals("resolve")?"RESOLVED":"APPROVED");
            data.put("reviewNote",note); data.put("reviewedAt",Instant.now().toString());
        } else throw new ApiException("Unknown action.");
        return store(p,kind,r.id(),r.employeeId(),data);
    }
    private void approveLeave(AuthPrincipal p,Record r) {
        db.queryForObject("select id from employees where id=? and company_id=? for update",Long.class,r.employeeId(),p.companyId());
        int year=LocalDate.parse(text(r.data(),"startDate")).getYear();
        var balances=all(p.companyId(),"leave-balance").stream().filter(x->r.employeeId().equals(x.employeeId())
                &&text(x.data(),"title").equals(text(r.data(),"title"))&&number(x.data(),"year").intValue()==year).toList();
        BigDecimal allowance=balances.stream().map(x->number(x.data(),"days")).reduce(BigDecimal.ZERO,BigDecimal::add);
        var approved=all(p.companyId(),"leave-request").stream().filter(x->r.employeeId().equals(x.employeeId())&&text(x.data(),"status").equals("APPROVED")).toList();
        LocalDate start=LocalDate.parse(text(r.data(),"startDate")),end=LocalDate.parse(text(r.data(),"endDate"));
        for(var x:approved) if(!LocalDate.parse(text(x.data(),"endDate")).isBefore(start)&&!LocalDate.parse(text(x.data(),"startDate")).isAfter(end))
            throw new ApiException("This employee already has approved leave in this date range.");
        BigDecimal used=approved.stream().filter(x->text(x.data(),"title").equals(text(r.data(),"title"))&&LocalDate.parse(text(x.data(),"startDate")).getYear()==year)
            .map(x->number(x.data(),"days")).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(used.add(number(r.data(),"days")).compareTo(allowance)>0) throw new ApiException("Not enough leave balance. Set an allowance first.");
    }
    private void applyProfile(AuthPrincipal p,Record r) {
        Employee e=employee(p,r.employeeId());
        if(r.data().containsKey("email")) {
            employees.findByCompanyIdAndEmailIgnoreCase(p.companyId(),text(r.data(),"email")).ifPresent(other->{
                if(!other.getId().equals(e.getId())) throw new ApiException("This email belongs to another employee.");
            });
            e.setEmail(text(r.data(),"email"));
        }
        if(r.data().containsKey("phone")) e.setPhone(text(r.data(),"phone"));
        e.setUpdatedAt(Instant.now()); employees.save(e);
    }
    @Transactional
    public Record hire(AuthPrincipal p,long candidateId,Map<String,Object> body) {
        if(!hr(p)) throw new ApiException("Only HR or Admin can convert a candidate.",403);
        Record r=get(p,"candidate",candidateId);
        db.queryForObject("select id from hr_records where id=? for update",Long.class,r.id());
        r=get(p,"candidate",candidateId);
        if(!text(r.data(),"status").equals("HIRED") || r.data().containsKey("employeeRecordId")) throw new ApiException("Mark the candidate hired first; each candidate can be converted once.");
        Record job=get(p,"job-posting",id(r.data(),"jobId"));
        String name=text(r.data(),"title");
        String[] parts=name.split("\\s+",2);
        LocalDate start;
        try { start=LocalDate.parse(text(body,"startDate")); } catch(Exception e) { throw new ApiException("Choose a start date."); }
        var created=employeeService.create(p,new EmployeeService.EmployeeInput(null,parts[0],parts.length>1?parts[1]:"—",
            text(r.data(),"email"),null,id(job.data(),"positionId"),null,null,null,start,"ACTIVE"),BigDecimal.ZERO,"Initial salary pending Finance");
        Map<String,Object> data=new LinkedHashMap<>(r.data()); data.put("employeeRecordId",created.id());
        store(p,"onboarding",null,created.id(),new LinkedHashMap<>(Map.of("title","Complete your profile","assignedTo","EMPLOYEE","dueDate",start.plusDays(7).toString(),"status","TODO")));
        store(p,"onboarding",null,created.id(),new LinkedHashMap<>(Map.of("title","Welcome meeting and first-week plan","assignedTo","MANAGER","dueDate",start.plusDays(7).toString(),"status","TODO")));
        return store(p,"candidate",r.id(),null,data);
    }
    @Transactional
    public int importMetrics(AuthPrincipal p,Reader source) throws IOException {
        int count=0;
        try(CSVParser csv=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(source)) {
            for(CSVRecord row:csv) {
                if(++count>2000) throw new ApiException("Import at most 2,000 metric rows at once.");
                Map<String,Object> data=new LinkedHashMap<>();
                for(String key:List.of("title","metricId","date","value","evidence")) data.put(key,row.get(key));
                save(p,"metric-value",null,Long.valueOf(row.get("employeeId")),data);
            }
        } catch(IllegalArgumentException e) { throw new ApiException("Use CSV columns employeeId,title,metricId,date,value,evidence."); }
        return count;
    }
    public String export(AuthPrincipal p,String kind,Long employeeId) {
        List<Record> records=list(p,kind,employeeId);
        try {
            StringWriter writer=new StringWriter();
            List<String> headers=new ArrayList<>(List.of("id","employeeId","createdAt"));
            headers.addAll(HrCatalog.get(kind).fields().stream().map(HrCatalog.Field::key).toList());
            try(CSVPrinter csv=new CSVPrinter(writer,CSVFormat.DEFAULT)) {
                csv.printRecord(headers);
                for(Record r:records) {
                    List<Object> values=new ArrayList<>(List.of(r.id(),r.employeeId()==null?"":r.employeeId(),r.createdAt()));
                    for(var field:HrCatalog.get(kind).fields()) values.add(CsvSafety.safe(text(r.data(),field.key())));
                    csv.printRecord(values);
                    audit.record(p,p.companyId(),"HR_EXPORTED",kind,r.id(),Map.of());
                }
            }
            return writer.toString();
        } catch(IOException ex) { throw new IllegalStateException(ex); }
    }
    public Map<String,Object> insights(AuthPrincipal p,Long employeeId) {
        Map<String,Object> out=new LinkedHashMap<>();
        LocalDate today=LocalDate.now(ZoneId.of(text(settings(p.companyId()),"timezone")));out.put("today",today.toString());
        var feedback=list(p,"feedback",employeeId);
        long positive=feedback.stream().filter(r->text(r.data(),"type").equals("POSITIVE")).count();
        out.put("positive",positive); out.put("constructive",feedback.size()-positive);
        var actions=list(p,"discipline",employeeId);
        var steps=list(p,"discipline-step",null).stream().sorted(Comparator.comparing(r->number(r.data(),"order"))).toList();
        if(employeeId!=null) {
            BigDecimal highest=actions.stream().map(r->steps.stream().filter(s->s.id()==id(r.data(),"stepId")).findFirst().map(s->number(s.data(),"order")).orElse(BigDecimal.ZERO)).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            out.put("nextDisciplineStep",steps.stream().filter(s->number(s.data(),"order").compareTo(highest)>0).findFirst().map(r->text(r.data(),"title")).orElse("No further configured step"));
        }
        out.put("disciplineConsistency",actions.stream().collect(java.util.stream.Collectors.groupingBy(r->text(r.data(),"title"),
            java.util.stream.Collectors.groupingBy(r->text(r.data(),"stepId"),java.util.stream.Collectors.counting()))));
        out.put("skills",list(p,"skill",employeeId));
        out.put("trend",list(p,"review",employeeId).stream().filter(r->text(r.data(),"status").equals("SHARED")).map(r->{
            Map<?,?> scores=(Map<?,?>)r.data().getOrDefault("scores",Map.of());
            double avg=scores.values().stream().mapToDouble(v->Double.parseDouble(v.toString())).average().orElse(0);
            return Map.of("date",text(r.data(),"dueDate"),"score",avg,"employeeId",r.employeeId());
        }).toList());
        var tasks=list(p,"task",employeeId);
        Set<Long> visibleTasks=new HashSet<>(tasks.stream().map(Record::id).toList());
        Map<String,Object> taskHours=new LinkedHashMap<>();
        db.queryForList("select task_id,round(sum(extract(epoch from (coalesce(clock_out,now())-clock_in)))/3600,2) as hours from attendance_entries where company_id=? and task_id is not null group by task_id",p.companyId())
            .forEach(row->{long id=((Number)row.get("task_id")).longValue();if(visibleTasks.contains(id))taskHours.put(String.valueOf(id),row.get("hours"));});
        out.put("taskHours",taskHours);
        out.put("overdueTasks",tasks.stream().filter(r->!text(r.data(),"status").equals("DONE")&&LocalDate.parse(text(r.data(),"dueDate")).isBefore(today)).toList());
        out.put("missedDeadlines",tasks.stream().filter(r->!text(r.data(),"status").equals("DONE")
                ?LocalDate.parse(text(r.data(),"dueDate")).isBefore(today)
                :r.data().containsKey("completedAt")&&Instant.parse(text(r.data(),"completedAt")).atZone(ZoneId.of(text(settings(p.companyId()),"timezone"))).toLocalDate().isAfter(LocalDate.parse(text(r.data(),"dueDate")))).toList());
        out.put("workload",tasks.stream().filter(r->!text(r.data(),"status").equals("DONE")).collect(java.util.stream.Collectors.groupingBy(Record::employeeId,java.util.stream.Collectors.counting())));
        return out;
    }
    public List<Map<String,Object>> reminders(AuthPrincipal p) {
        List<Map<String,Object>> out=new ArrayList<>();
        LocalDate today=LocalDate.now(ZoneId.of(text(settings(p.companyId()),"timezone")));
        for(String kind:List.of("review","task","onboarding")) for(Record r:list(p,kind,null)) {
            String due=text(r.data(),"dueDate"),status=text(r.data(),"status");
            if(!due.isBlank()&&!LocalDate.parse(due).isAfter(today)&&!Set.of("DONE","SHARED").contains(status))
                out.add(Map.of("kind",kind,"title",text(r.data(),"title"),"employeeId",r.employeeId(),"dueDate",due));
        }
        if(hr(p)||p.role()==AccessRole.MANAGER) for(Employee e:employees.findByCompanyIdOrderByFirstNameAscLastNameAsc(p.companyId())) {
            if(!manages(p,e.getId())||!e.getStatus().equals("ACTIVE")) continue;
            LocalDate latest=all(p.companyId(),"feedback").stream().filter(r->e.getId().equals(r.employeeId())).map(r->LocalDate.parse(text(r.data(),"date"))).max(LocalDate::compareTo).orElse(e.getStartDate());
            if(latest.plusDays(number(settings(p.companyId()),"feedbackDays").longValue()).isBefore(today))
                out.add(Map.of("kind","feedback","title","Log feedback for "+e.fullName(),"employeeId",e.getId(),"dueDate",latest.toString()));
        }
        if(p.employeeId()!=null) {
            LocalDate previous=today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            Instant start=previous.atStartOfDay(ZoneId.of(text(settings(p.companyId()),"timezone"))).toInstant();
            Instant end=previous.plusWeeks(1).atStartOfDay(ZoneId.of(text(settings(p.companyId()),"timezone"))).toInstant();
            Long recorded=db.queryForObject("select count(*) from attendance_entries where company_id=? and employee_id=? and clock_in<? and clock_out>?",Long.class,p.companyId(),p.employeeId(),java.sql.Timestamp.from(end),java.sql.Timestamp.from(start));
            Long submitted=db.queryForObject("select count(*) from timesheets where company_id=? and employee_id=? and week_start=? and status<>'REJECTED'",Long.class,p.companyId(),p.employeeId(),java.sql.Date.valueOf(previous));
            if(recorded!=null&&recorded>0&&submitted!=null&&submitted==0) out.add(Map.of("kind","timesheet","title","Submit last week’s timesheet","employeeId",p.employeeId(),"dueDate",previous.toString()));
        }
        return out;
    }
    public List<Record> approvals(AuthPrincipal p) {
        List<Record> out=new ArrayList<>();
        for(String kind:List.of("leave-request","development-request","profile-request","help-request","attendance-request"))
            out.addAll(list(p,kind,null).stream().filter(r->text(r.data(),"status").equals("PENDING")&&!self(p,r.employeeId())&&manages(p,r.employeeId())
                &&(!Set.of("profile-request","help-request").contains(kind)||hr(p))).toList());
        return out;
    }
    public List<Map<String,Object>> leaveBalances(AuthPrincipal p) {
        var approved=list(p,"leave-request",null).stream().filter(r->text(r.data(),"status").equals("APPROVED")).toList();
        return list(p,"leave-balance",null).stream().map(r->{
            BigDecimal used=approved.stream().filter(x->Objects.equals(x.employeeId(),r.employeeId())
                    &&text(x.data(),"title").equals(text(r.data(),"title"))&&LocalDate.parse(text(x.data(),"startDate")).getYear()==number(r.data(),"year").intValue())
                    .map(x->number(x.data(),"days")).reduce(BigDecimal.ZERO,BigDecimal::add);
            return Map.<String,Object>of("employeeId",r.employeeId(),"title",text(r.data(),"title"),"year",r.data().get("year"),"allowance",number(r.data(),"days"),"used",used,"remaining",number(r.data(),"days").subtract(used));
        }).toList();
    }
    public static String text(Map<String,Object> data,String key) { return data.get(key)==null?"":String.valueOf(data.get(key)); }
    private boolean usersForInterview(AuthPrincipal p,long employeeId) {
        return db.queryForObject("select count(*) from users where company_id=? and employee_id=? and status='ACTIVE' and access_role in ('ADMIN','HR','MANAGER')",Long.class,p.companyId(),employeeId)>0;
    }
    private Instant[] correctionInterval(AuthPrincipal p,Map<String,Object> data) {
        ZoneId zone=ZoneId.of(text(settings(p.companyId()),"timezone"));
        Instant start=LocalDateTime.parse(text(data,"clockIn")).atZone(zone).toInstant(),end=LocalDateTime.parse(text(data,"clockOut")).atZone(zone).toInstant();
        if(!end.isAfter(start)||end.isAfter(Instant.now())||Duration.between(start,end).toHours()>=24) throw new ApiException("Use a completed work interval shorter than 24 hours, with no future time.");
        if(data.containsKey("entryId")) id(data,"entryId");
        return new Instant[]{start,end};
    }
    private void applyTimeCorrection(AuthPrincipal p,Record r,Map<String,Object> data) {
        ZoneId zone=ZoneId.of(text(settings(p.companyId()),"timezone"));
        Instant[] interval=correctionInterval(p,r.data()); Instant start=interval[0],end=interval[1];
        db.queryForObject("select id from employees where company_id=? and id=? for update",Long.class,p.companyId(),r.employeeId());
        Long entryId=id(r.data(),"entryId");
        Instant oldStart=start,oldEnd=end;
        if(entryId!=null) {
            var old=db.queryForList("select clock_in,clock_out from attendance_entries where id=? and company_id=? and employee_id=?",entryId,p.companyId(),r.employeeId());
            if(old.isEmpty()) throw new ApiException("This session does not belong to the requesting employee.",404);
            oldStart=((java.sql.Timestamp)old.getFirst().get("clock_in")).toInstant();
            oldEnd=old.getFirst().get("clock_out")==null?Instant.now():((java.sql.Timestamp)old.getFirst().get("clock_out")).toInstant();
            data.put("previousStart",oldStart.toString());data.put("previousFinish",oldEnd.toString());
        }
        for(Instant[] range:List.of(new Instant[]{start,end},new Instant[]{oldStart,oldEnd})) {
            LocalDate from=range[0].atZone(zone).toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate to=range[1].atZone(zone).toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if(db.queryForObject("select count(*) from timesheets where company_id=? and employee_id=? and week_start between ? and ? and status<>'REJECTED'",Long.class,p.companyId(),r.employeeId(),java.sql.Date.valueOf(from),java.sql.Date.valueOf(to))>0)
                throw new ApiException("Reject the submitted timesheet before applying this correction.");
        }
        if(db.queryForObject("select count(*) from attendance_entries where company_id=? and employee_id=? and id<>? and clock_in<? and coalesce(clock_out,'infinity')>?",Long.class,p.companyId(),r.employeeId(),entryId==null?-1:entryId,java.sql.Timestamp.from(end),java.sql.Timestamp.from(start))>0)
            throw new ApiException("The corrected time overlaps another work session.");
        if(entryId==null) db.update("insert into attendance_entries(company_id,employee_id,clock_in,clock_out,work_summary) values(?,?,?,?,?)",p.companyId(),r.employeeId(),java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),"Approved correction: "+text(r.data(),"title").substring(0,Math.min(150,text(r.data(),"title").length())));
        else db.update("update attendance_entries set clock_in=?,clock_out=? where id=? and company_id=?",java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),entryId,p.companyId());
        audit.record(p,p.companyId(),"ATTENDANCE_CORRECTED","EMPLOYEE",r.employeeId(),Map.of("requestId",r.id()));
    }
    private void checkTaskDependency(AuthPrincipal p,Map<String,Object> data) {
        Long dependency=id(data,"dependsOnId");
        if(dependency!=null&&all(p.companyId(),"task").stream().noneMatch(r->r.id()==dependency&&text(r.data(),"status").equals("DONE"))) throw new ApiException("Complete the prerequisite task first, or ask your manager to change the dependency.");
    }
    public List<Record> rewardReviews(AuthPrincipal p) {
        if(p.role()!=AccessRole.CFO&&!hr(p)) throw new ApiException("Only Finance, HR or Admin can open reward references.",403);
        // Finance needs the evidence reference, not the confidential review notes or scores.
        return all(p.companyId(),"review").stream().filter(r->text(r.data(),"status").equals("SHARED"))
            .map(r->new Record(r.id(),r.kind(),r.employeeId(),r.ownerId(),Map.of("title",text(r.data(),"title")),r.createdAt(),r.updatedAt())).toList();
    }
    public Map<String,Object> home(AuthPrincipal p) {
        Map<String,Integer> counts=new LinkedHashMap<>(); List<Map<String,Object>> focus=new ArrayList<>();
        for(var module:HrCatalog.MODULES) {
            var visible=all(p.companyId(),module.key()).stream().filter(r->readable(p,r)).toList();
            counts.put(module.key(),visible.size());
            if(!Set.of("task","interview","leave-request","development-request","attendance-request","profile-request","help-request","onboarding","review").contains(module.key())) continue;
            for(var r:visible) {
                String status=text(r.data(),"status");
                if(Set.of("DONE","COMPLETED","CANCELLED","REJECTED","APPROVED","SHARED").contains(status)) continue;
                String due=text(r.data(),r.kind().equals("interview")?"date":r.kind().equals("leave-request")?"startDate":"dueDate");
                Map<String,Object> item=new LinkedHashMap<>(); item.put("id",r.id()); item.put("kind",r.kind());
                item.put("title",text(r.data(),"title")); item.put("status",status); item.put("date",due);
                item.put("employeeId",r.employeeId()); item.put("group",module.group()); focus.add(item);
            }
        }
        focus.sort(Comparator.comparing(i->Objects.toString(i.get("date"),"")));
        long pendingSheets=db.queryForList("select employee_id from timesheets where company_id=? and status='SUBMITTED'",Long.class,p.companyId()).stream()
            .filter(id->!self(p,id)&&manages(p,id)).count();
        return Map.of("counts",counts,"focus",focus.stream().limit(12).toList(),"pendingApprovals",approvals(p).size()+pendingSheets);
    }
    public static Long id(Map<String,Object> data,String key) {
        String value=text(data,key);
        try { return value.isBlank()?null:Long.valueOf(value); }
        catch(NumberFormatException ex) { throw new ApiException("Choose a valid record ID."); }
    }
    public static BigDecimal number(Map<String,Object> data,String key) { return new BigDecimal(text(data,key)); }
    static int workingDays(LocalDate start,LocalDate end) {
        return (int)start.datesUntil(end.plusDays(1)).filter(d->d.getDayOfWeek()!=DayOfWeek.SATURDAY&&d.getDayOfWeek()!=DayOfWeek.SUNDAY).count();
    }
}
