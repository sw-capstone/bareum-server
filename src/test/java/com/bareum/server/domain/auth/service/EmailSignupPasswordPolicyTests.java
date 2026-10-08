package com.bareum.server.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EmailSignupPasswordPolicyTests {

    private final EmailSignupPasswordPolicy policy = new EmailSignupPasswordPolicy();

    @ParameterizedTest(name = "allowed special character {index}")
    @MethodSource("passwordsWithAllowedSpecialCharacters")
    void acceptsEachAllowedSpecialCharacterAtMinimumLength(String password) {
        assertTrue(policy.isValid(password));
    }

    static Stream<String> passwordsWithAllowedSpecialCharacters() {
        return "!@#$%&".chars().mapToObj(character -> "a".repeat(10) + "1" + (char) character);
    }

    @Test
    void acceptsUppercaseLettersWithoutRequiringLowercaseLetters() {
        assertTrue(policy.isValid("A".repeat(10) + "1!"));
    }

    @Test
    void acceptsMoreThanTwelveCharactersWithoutUsingOldApiMaximum() {
        assertTrue(policy.isValid("a".repeat(11) + "1!"));
    }

    @ParameterizedTest(name = "invalid input {index}")
    @MethodSource("invalidPasswords")
    void rejectsMissingRequirementsAndCharactersOutsideDraftAlphabet(String password) {
        assertFalse(policy.isValid(password));
    }

    static Stream<String> invalidPasswords() {
        return Stream.of(
                null,
                "",
                "a".repeat(9) + "1!",
                "a".repeat(12),
                "1".repeat(11) + "!",
                "a".repeat(11) + "!",
                "a".repeat(11) + "1",
                "a".repeat(10) + "1*",
                "a".repeat(10) + "1!" + "*",
                "a".repeat(10) + "1!" + " ",
                " " + "a".repeat(10) + "1!",
                "a".repeat(5) + " " + "a".repeat(5) + "1!",
                "a".repeat(10) + "1!" + "\t",
                "a".repeat(10) + "1!" + "\n",
                "a".repeat(10) + "1!" + "\r",
                "a".repeat(10) + "1!" + "\u00a0",
                "a".repeat(10) + "1!" + "\u3000",
                "a".repeat(10) + "1!" + "\u200b",
                "가".repeat(10) + "1!",
                "a".repeat(10) + "１!",
                "a".repeat(10) + "1！"
        );
    }
}
