package com.empmg.platform.compensation;

import com.empmg.platform.compensation.service.RaiseService;
import com.empmg.platform.compensation.service.SalaryReversalRequest;
import com.empmg.platform.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RaiseController {

    private final RaiseService raiseService;

    @PostMapping("/raises/preview")
    public RaiseService.Preview preview(@RequestBody RaiseService.RaiseRequest req) {
        return raiseService.preview(SecurityUtils.current(), req);
    }

    @PostMapping("/raises")
    public RaiseService.RaiseSummary create(@RequestBody RaiseService.RaiseRequest req) {
        return raiseService.create(SecurityUtils.current(), req);
    }

    @GetMapping("/raises")
    public List<RaiseService.RaiseSummary> list(@RequestParam(required = false) List<String> status) {
        return raiseService.list(SecurityUtils.current(), status);
    }

    @GetMapping("/raises/{id}")
    public RaiseService.RaiseDetail detail(@PathVariable Long id) {
        return raiseService.detail(SecurityUtils.current(), id);
    }

    @PostMapping("/raises/{id}/approve")
    public RaiseService.RaiseSummary approve(@PathVariable Long id) {
        return raiseService.approve(SecurityUtils.current(), id);
    }

    @PostMapping("/raises/{id}/reject")
    public RaiseService.RaiseSummary reject(@PathVariable Long id) {
        return raiseService.reject(SecurityUtils.current(), id);
    }

    @PostMapping("/raises/{id}/cancel")
    public RaiseService.RaiseSummary cancel(@PathVariable Long id) {
        return raiseService.cancel(SecurityUtils.current(), id);
    }

    @PostMapping("/salary-history/{id}/reverse")
    public Map<String, Object> reverse(@PathVariable Long id,
                                       @RequestBody(required = false) SalaryReversalRequest body) {
        return raiseService.reverseHistoryEntry(SecurityUtils.current(), id,
                body == null ? null : body.reason());
    }
}
