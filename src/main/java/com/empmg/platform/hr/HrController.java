package com.empmg.platform.hr;

import com.empmg.platform.common.ApiException;
import com.empmg.platform.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/hr")
@RequiredArgsConstructor
public class HrController {
    private final HrService hr;
    public record Input(Long employeeId,Map<String,Object> data) {}
    @GetMapping("/catalog") public List<HrCatalog.Module> catalog() { return HrCatalog.MODULES; }
    @GetMapping("/home") public Object home() { return hr.home(SecurityUtils.current()); }
    @GetMapping("/reward-reviews") public Object rewardReviews() { return hr.rewardReviews(SecurityUtils.current()); }
    @GetMapping("/insights") public Map<String,Object> insights(@RequestParam(required=false) Long employeeId) { return hr.insights(SecurityUtils.current(),employeeId); }
    @GetMapping("/reminders") public Object reminders() { return hr.reminders(SecurityUtils.current()); }
    @GetMapping("/approvals") public Object approvals() { return hr.approvals(SecurityUtils.current()); }
    @GetMapping("/leave-balances") public Object leaveBalances() { return hr.leaveBalances(SecurityUtils.current()); }
    @GetMapping("/records/{kind}") public Object list(@PathVariable String kind,@RequestParam(required=false) Long employeeId) { return hr.list(SecurityUtils.current(),kind,employeeId); }
    @PostMapping("/records/{kind}") public Object create(@PathVariable String kind,@RequestBody Input input) {
        if(input.data()==null) throw new ApiException("Enter the record details.");
        return hr.save(SecurityUtils.current(),kind,null,input.employeeId(),input.data());
    }
    @PutMapping("/records/{kind}/{id}") public Object edit(@PathVariable String kind,@PathVariable long id,@RequestBody Input input) {
        if(input.data()==null) throw new ApiException("Enter the record details.");
        return hr.save(SecurityUtils.current(),kind,id,input.employeeId(),input.data());
    }
    @PostMapping("/records/{kind}/{id}/{action}") public Object action(@PathVariable String kind,@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> body) {
        return hr.action(SecurityUtils.current(),kind,id,action,body);
    }
    @PostMapping("/candidates/{id}/convert") public Object convert(@PathVariable long id,@RequestBody Map<String,Object> body) { return hr.hire(SecurityUtils.current(),id,body); }
    @PostMapping("/metrics/import") public Object importMetrics(@RequestParam MultipartFile file) throws IOException {
        if(file.isEmpty()||file.getSize()>5*1024*1024||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv")) throw new ApiException("Upload a CSV file smaller than 5 MB.");
        try(var reader=new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8)) { return Map.of("imported",hr.importMetrics(SecurityUtils.current(),reader)); }
    }
    @GetMapping("/export/{kind}") public ResponseEntity<byte[]> export(@PathVariable String kind,@RequestParam(required=false) Long employeeId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+kind+".csv\"")
            .body(hr.export(SecurityUtils.current(),kind,employeeId).getBytes(StandardCharsets.UTF_8));
    }
}
