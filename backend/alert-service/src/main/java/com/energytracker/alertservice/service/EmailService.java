package com.energytracker.alertservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class EmailService {

        private final JavaMailSender mailSender;
        private final String fromAddress;

        public EmailService(JavaMailSender mailSender,
                        @Value("${app.mail.from:energy-tracker@example.com}") String fromAddress) {
                this.mailSender = mailSender;
                this.fromAddress = fromAddress;
        }

        public void sendEmail(String to, String subject, String body) {
                final SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromAddress);
                message.setTo(to);
                message.setSubject(subject);
                message.setText(body);

                mailSender.send(message);
                log.info("Email sent to {}", to);
        }
}
