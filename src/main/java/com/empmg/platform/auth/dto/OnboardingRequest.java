package com.empmg.platform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OnboardingRequest(
        @NotBlank(message = "Company name is required.") String companyName,
        @NotBlank @Email String email,
        @NotBlank String password) {
}
