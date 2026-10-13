package com.bareum.server.domain.auth.service;

import org.springframework.stereotype.Component;

@Component
public class EmailSignupPasswordPolicy {

    private static final int MIN_LENGTH = 12;
    private static final String ALLOWED_SPECIAL_CHARACTERS = "!@#$%&";

    public boolean isValid(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecialCharacter = false;

        for (int character : password.codePoints().toArray()) {
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                return false;
            }
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')) {
                hasLetter = true;
            } else if (character >= '0' && character <= '9') {
                hasDigit = true;
            } else if (ALLOWED_SPECIAL_CHARACTERS.indexOf(character) >= 0) {
                hasSpecialCharacter = true;
            }
        }

        return hasLetter && hasDigit && hasSpecialCharacter;
    }
}
