package com.nxtshift.app;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.regex.Pattern;

public class EmailAuthValidationTest {

    // RFC-compliant email pattern for unit testing in JVM environment
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    @Test
    public void testValidEmailFormats() {
        assertTrue(isValidEmail("nurse.jane@hospital.org"));
        assertTrue(isValidEmail("user+shift@domain.co.in"));
        assertTrue(isValidEmail("staff_123@sub.med.edu"));
        assertTrue(isValidEmail("alex@hospital.com"));
    }

    @Test
    public void testInvalidEmailFormats() {
        assertFalse(isValidEmail(""));
        assertFalse(isValidEmail(null));
        assertFalse(isValidEmail("plainaddress"));
        assertFalse(isValidEmail("@missingusername.com"));
        assertFalse(isValidEmail("missingdomain@"));
        assertFalse(isValidEmail("user@.com"));
    }

    @Test
    public void testPasswordLengthValidation() {
        assertFalse(isValidPassword(null));
        assertFalse(isValidPassword(""));
        assertFalse(isValidPassword("12345")); // 5 chars - too short
        assertTrue(isValidPassword("123456")); // 6 chars - minimum valid length
        assertTrue(isValidPassword("SecurePassword2026!"));
    }

    @Test
    public void testPasswordConfirmationMatch() {
        String pass = "ShiftPassword123";
        String confirmSame = "ShiftPassword123";
        String confirmDiff = "ShiftPassword456";

        assertTrue(pass.equals(confirmSame));
        assertFalse(pass.equals(confirmDiff));
    }

    private boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        return EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    private boolean isValidPassword(String password) {
        return password != null && password.length() >= 6;
    }
}
