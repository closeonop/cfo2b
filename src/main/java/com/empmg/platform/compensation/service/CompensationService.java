package com.empmg.platform.compensation.service;

import com.empmg.platform.compensation.domain.VariableComponent;
import com.empmg.platform.employee.domain.Employee;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Computes total compensation for an employee and company aggregates. All in BigDecimal. */
@Service
public class CompensationService {

    public record Breakdown(BigDecimal baseMonthly, BigDecimal variableMonthly, BigDecimal oneTimeTotal) {
        public BigDecimal totalMonthly() {
            return baseMonthly.add(variableMonthly);
        }
    }

    /** Monthly-equivalent breakdown. One-time items are reported separately. */
    public Breakdown monthly(Employee e, List<VariableComponent> components, String salaryPeriod) {
        BigDecimal base = MoneyMath.monthlyOf(e.getBaseSalary(), salaryPeriod);
        BigDecimal variable = BigDecimal.ZERO;
        BigDecimal oneTime = BigDecimal.ZERO;
        for (VariableComponent c : components) {
            if (!c.isActive()) continue;
            if (VariableComponent.ONE_TIME.equals(c.getPeriod())) {
                oneTime = oneTime.add(c.getValue());
                continue;
            }
            if (VariableComponent.PERCENT.equals(c.getCalcType())) {
                variable = variable.add(base.multiply(c.getValue())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            } else {
                variable = variable.add(c.monthlyEquivalent());
            }
        }
        return new Breakdown(base, variable.setScale(2, RoundingMode.HALF_UP),
                oneTime.setScale(2, RoundingMode.HALF_UP));
    }
}
