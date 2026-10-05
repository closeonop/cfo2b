package com.empmg.platform.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
}
