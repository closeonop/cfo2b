package com.empmg.platform.hr;

import com.empmg.platform.common.ApiException;
import java.util.*;

/** The server owns required fields, allowed values and reference types. */
public final class HrCatalog {
    private HrCatalog() {}
    public record Field(String key, String label, String type, boolean required, List<String> options, String reference) {}
    public record Module(String key, String label, String group, String description, boolean employeeScoped,
                         String access, List<Field> fields) {}
    static Field f(String key, String label, String type, boolean required) {
        return new Field(key, label, type, required, List.of(), null);
    }
    static Field choice(String key, String label, String... options) {
        return new Field(key, label, "select", true, List.of(options), null);
    }
    static Field ref(String key, String label, String kind, boolean required) {
        return new Field(key, label, "reference", required, List.of(), kind);
    }
    static Module m(String key, String label, String group, String description, boolean scoped, String access, Field... fields) {
        return new Module(key, label, group, description, scoped, access, List.of(fields));
    }
    public static final List<Module> MODULES = List.of(
        m("job-posting", "Job postings", "Hiring", "Describe an opening before adding candidates.", false, "hiring",
            f("title","Job title","text",true), ref("positionId","Position","position",true), f("description","Description","textarea",true), choice("status","Status","OPEN","CLOSED")),
        m("candidate","Candidates","Hiring","Move candidates through screening, interviews and offers.",false,"hiring",
            f("title","Candidate name","text",true),f("email","Email","email",true),ref("jobId","Job posting","job-posting",true),
            choice("status","Stage","APPLIED","SCREENING","INTERVIEW","OFFER","HIRED","REJECTED"),f("notes","Notes","textarea",false)),
        m("interview","Interviews","Hiring","Assign an interviewer and time, then record evidence after the conversation.",false,"hiring",
            f("title","Interview title","text",true),ref("candidateId","Candidate","candidate",true),f("date","Interview date","date",true),
            ref("interviewerId","Interviewer","employee",false),f("time","Start time","time",false),
            new Field("status","Interview status","select",false,List.of("DRAFT","SCHEDULED","COMPLETED","CANCELLED"),null),
            f("score","Score (0-5)","score",false),f("notes","Interview notes","textarea",false)),
        m("onboarding","Onboarding checklist","Hiring","Assign the first weeks of work to a new hire or their manager.",true,"managed",
            f("title","Checklist item","text",true),choice("assignedTo","Responsible person","EMPLOYEE","MANAGER"),f("dueDate","Due date","date",true),choice("status","Status","TODO","DONE")),
        m("metric","Position metrics","Performance","Define what good performance means for each position.",false,"definition",
            f("title","Metric name","text",true),ref("positionId","Position","position",true),f("unit","Unit","text",true),f("target","Target","number",true),choice("direction","Better when","HIGHER","LOWER")),
        m("metric-value","Metric values","Performance","Record dated evidence against the employee’s position metrics.",true,"managed",
            f("title","Evidence summary","text",true),ref("metricId","Metric","metric",true),f("date","Measured on","date",true),f("value","Value","number",true),f("evidence","Evidence","textarea",true)),
        m("review","Performance reviews","Performance","Schedule a review, then score only the position’s metrics.",true,"managed",
            f("title","Review name","text",true),f("dueDate","Review due","date",true),choice("status","Status","SCHEDULED","SHARED"),f("scores","Metric scores","scores",false),f("notes","Review comments","textarea",false)),
        m("meeting","1:1 meetings","Communication","Record expectations and a two-way conversation.",true,"managed",
            f("title","Meeting title","text",true),f("date","Meeting date","date",true),f("expectations","Expectations","textarea",true),f("why","Why this matters","textarea",true),f("feedback","Feedback","textarea",true)),
        m("feedback","Feedback","Communication","Give positive or constructive feedback; employees can reply.",true,"managed",
            f("title","Feedback summary","text",true),choice("type","Feedback type","POSITIVE","CONSTRUCTIVE"),f("date","Date","date",true),f("notes","Feedback","textarea",true)),
        m("skill","Skills","Development","Keep an employee skills profile and team overview.",true,"self",
            f("title","Skill","text",true),choice("level","Level","BEGINNER","WORKING","PROFICIENT","EXPERT")),
        m("goal","Development goals","Development","Agree a goal, due date and visible progress.",true,"self",
            f("title","Goal","text",true),f("dueDate","Due date","date",true),f("progress","Progress (%)","progress",true),f("notes","Success criteria","textarea",true)),
        m("training","Training items","Development","Add a course, mentoring or a project to a goal.",true,"self",
            f("title","Training item","text",true),ref("goalId","Goal","goal",true),choice("type","Type","COURSE","MENTORING","PROJECT"),choice("status","Status","PLANNED","IN_PROGRESS","DONE")),
        m("development-request","Development requests","Development","Request an opportunity for manager approval.",true,"request",
            f("title","Opportunity","text",true),f("notes","How it helps","textarea",true),f("dueDate","Requested by","date",false)),
        m("recognition","Recognition","Rewards","Recognize an employee’s contribution.",true,"managed",
            f("title","Recognition","text",true),f("date","Date","date",true),f("notes","Contribution","textarea",true)),
        m("reward","Reward records","Rewards","Link bonuses, raises, promotions or awards to performance evidence. Pay changes use Compensation.",true,"reward",
            f("title","Reward","text",true),choice("type","Type","BONUS","RAISE","PROMOTION","AWARD"),ref("reviewId","Performance review","review",true),f("date","Date","date",true),f("notes","Reward details","textarea",true)),
        m("discipline-step","Discipline policy","Discipline","Define ordered progressive steps before recording an action.",false,"definition",
            f("title","Policy step","text",true),f("order","Step order","positive",true),f("policy","Policy / rule","textarea",true)),
        m("discipline","Disciplinary actions","Discipline","Document evidence, the employee’s side and support offered.",true,"personnel",
            f("title","Incident category","text",true),ref("stepId","Policy step","discipline-step",true),f("date","Incident date","date",true),f("reason","Reason","textarea",true),f("policyViolated","Policy violated","textarea",true),f("evidence","Evidence","textarea",true),f("employeeSide","Employee’s side","textarea",true),f("support","Support offered","textarea",true)),
        m("project","Projects","Work","Define a project before assigning tasks.",false,"project",
            f("title","Project name","text",true),f("notes","Purpose","textarea",true),f("dueDate","Due date","date",true),choice("status","Status","ACTIVE","DONE")),
        m("task","Tasks","Work","Assign work with a due date, purpose and downstream impact.",true,"managed",
            f("title","Task","text",true),ref("projectId","Project","project",true),f("dueDate","Due date","date",true),f("why","Why this matters","textarea",true),f("impact","Impact if delayed","textarea",true),ref("dependsOnId","Depends on task","task",false),choice("status","Status","TODO","IN_PROGRESS","BLOCKED","DONE")),
        m("leave-balance","Leave balances","Leave","Set an employee’s annual allowance. Approved requests consume weekdays.",true,"managed",
            f("title","Leave type","text",true),f("year","Year","positive",true),f("days","Allowance (days)","positive",true)),
        m("leave-request","Leave requests","Leave","Request working days off and route them for approval.",true,"request",
            f("title","Leave type","text",true),f("startDate","First day","date",true),f("endDate","Last day","date",true),f("notes","Reason","textarea",true)),
        m("profile-request","Profile changes","Self service","Request a contact detail update; HR reviews it before saving.",true,"request",
            f("title","Change summary","text",true),f("phone","New phone","text",false),f("email","New email","email",false)),
        m("attendance-request","Time corrections","Self service","Missed a check-in or check-out? Request a correction in the company time zone. A manager or HR reviews it.",true,"request",
            f("title","Correction summary","text",true),f("entryId","Existing session ID (leave blank for missing time)","positive",false),f("clockIn","Correct start (company time)","datetime",true),f("clockOut","Correct finish (company time)","datetime",true),f("notes","Reason","textarea",true)),
        m("policy","Company policies","Self service","Publish company policies and collect read acknowledgements.",false,"definition",
            f("title","Policy name","text",true),f("notes","Policy text","textarea",true)),
        m("help-request","HR help desk","Self service","Ask HR for help and keep the reply with your request.",true,"request",
            f("title","Subject","text",true),choice("category","Category","PAY","LEAVE","WORKPLACE","OTHER"),f("notes","Request","textarea",true)),
        m("hr-settings","HR settings","Administration","Set attendance expectations, reminder periods and retention.",false,"definition",
            f("title","Configuration name","text",true),f("timezone","Company time zone","text",true),f("weeklyHours","Expected weekly hours","positive",true),f("startTime","Workday starts","time",true),f("feedbackDays","Feedback reminder after (days)","positive",true),f("retentionDays","HR record retention (days)","positive",true))
    );
    public static Module get(String kind) {
        return MODULES.stream().filter(m -> m.key().equals(kind)).findFirst()
            .orElseThrow(() -> new ApiException("This module does not exist.",404));
    }
}
