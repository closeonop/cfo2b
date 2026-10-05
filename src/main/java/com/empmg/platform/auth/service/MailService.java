package com.empmg.platform.auth.service;

import com.empmg.platform.common.AppProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/** Sends emails (password reset). Locally, Mailpit catches everything at http://localhost:8025. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final AppProperties props;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.from:platform@local.test}")
    private String from;

    public void sendPasswordReset(String to, String resetLink) {
        if (!props.getMail().isEnabled()) {
            log.info("[mail disabled] Password reset link for {}: {}", to, resetLink);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject("Reset your password");
            helper.setText("""
                    <p>We received a request to reset your password.</p>
                    <p><a href="%s">Click here to choose a new password</a></p>
                    <p>This link works once and expires in 30 minutes. If you didn't ask for this, you can ignore this email.</p>
                    """.formatted(resetLink), true);
            mailSender.send(msg);
        } catch (MessagingException e) {
            // local dev has Mailpit; in dev failures shouldn't break the flow — log the link instead
            log.warn("Could not send email to {} ({}). Reset link (log it only in dev): {}",
                    to, e.getMessage(), resetLink);
        }
    }
}
