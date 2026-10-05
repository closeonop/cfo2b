package com.empmg.platform.compensation.domain;

import com.empmg.platform.common.EncryptedMoneyConverter;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "raise_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RaiseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raise_id", nullable = false)
    private Long raiseId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Convert(converter = EncryptedMoneyConverter.class)
    @Column(name = "old_salary", nullable = false, columnDefinition = "bytea")
    private BigDecimal oldSalary;

    @Convert(converter = EncryptedMoneyConverter.class)
    @Column(name = "new_salary", nullable = false, columnDefinition = "bytea")
    private BigDecimal newSalary;

    @Column(nullable = false)
    private boolean capped;
}
