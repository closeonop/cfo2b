package com.empmg.platform.auth.dto;

public record TokenResponse(String accessToken, UserDto user) {

    public record UserDto(Long id, String email, String role, Long companyId, Long employeeId, boolean passwordChangeRequired, boolean welcomeRequired, java.util.List<String> toursCompleted) {
        public static UserDto of(com.empmg.platform.user.domain.User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getAccessRole().name(), u.getCompanyId(), u.getEmployeeId(), u.isPasswordChangeRequired(), u.isWelcomeRequired(), java.util.List.copyOf(u.getToursCompleted()));
        }
    }
}
