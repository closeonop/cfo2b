package com.empmg.platform.hr;
import com.empmg.platform.common.ApiException;
import com.empmg.platform.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/attendance")
@RequiredArgsConstructor
public class AttendanceController {
    private final AttendanceService attendance;
    @GetMapping public Object weekly(@RequestParam(required=false) LocalDate week) {
        var p=SecurityUtils.current(); return attendance.weekly(p,week==null?LocalDate.now(attendance.zone(p)):week);
    }
    @PostMapping("/clock-in") public Object in(@RequestBody Map<String,Object> body) { return attendance.clock(SecurityUtils.current(),true,HrService.id(body,"taskId"),HrService.text(body,"workSummary"),"IN_PROGRESS"); }
    @PostMapping("/clock-out") public Object out(@RequestBody(required=false) Map<String,Object> body) {
        body=body==null?Map.of():body;
        return attendance.clock(SecurityUtils.current(),false,null,HrService.text(body,"workSummary"),body.containsKey("outcome")?HrService.text(body,"outcome"):"IN_PROGRESS");
    }
    @PostMapping("/switch-work") public Object switchWork(@RequestBody Map<String,Object> body) {
        return attendance.switchWork(SecurityUtils.current(),HrService.id(body,"taskId"),HrService.text(body,"workSummary"),HrService.text(body,"outcome"),HrService.text(body,"nextSummary"));
    }
    @PostMapping("/submit") public Object submit(@RequestBody Map<String,String> body) {
        try { return attendance.submit(SecurityUtils.current(),LocalDate.parse(body.get("weekStart"))); }
        catch(java.time.DateTimeException|NullPointerException e) { throw new ApiException("Choose a week."); }
    }
    @PostMapping("/timesheets/{id}/{action}") public Object review(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,String> body) {
        if(!action.equals("approve")&&!action.equals("reject")) throw new ApiException("Choose approve or reject.");
        return attendance.review(SecurityUtils.current(),id,action.equals("approve"),body.getOrDefault("note",""));
    }
    @GetMapping("/timesheets") public Object sheets() { return attendance.sheets(SecurityUtils.current()); }
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam LocalDate from,@RequestParam LocalDate to) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"approved-hours.csv\"")
            .body(attendance.export(SecurityUtils.current(),from,to).getBytes(StandardCharsets.UTF_8));
    }
}
