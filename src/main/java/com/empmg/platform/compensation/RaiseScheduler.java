package com.empmg.platform.compensation;

import com.empmg.platform.compensation.domain.Raise;
import com.empmg.platform.compensation.repo.RaiseRepo;
import com.empmg.platform.compensation.service.RaiseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Applies approved, future-dated raises automatically on their effective date.
 * Runs hourly; an admin approving a raise dated today also applies it immediately.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RaiseScheduler {

    private final RaiseRepo raiseRepo;
    private final RaiseService raiseService;

    @Scheduled(cron = "0 7 * * * *")
    public void applyDueRaises() {
        List<Raise> due = raiseRepo.findByStatusAndEffectiveDateLessThanEqual(Raise.APPROVED, LocalDate.now());
        for (Raise raise : due) {
            try {
                raiseService.applyIfDue(raise, null);
                log.info("Applied raise {} for company {}", raise.getId(), raise.getCompanyId());
            } catch (Exception e) {
                log.error("Failed to apply raise {}", raise.getId(), e);
            }
        }
    }
}
