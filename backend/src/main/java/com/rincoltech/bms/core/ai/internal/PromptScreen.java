package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The gateway's second line of defence against personal data (spec 5.4 and 5.12; the first line is the
 * typed fact list of the slice that builds the prompts, and the question scrub of the slice that takes a
 * question). The port accepts any text, so before a request is reserved, let alone sent, every message is
 * looked at for the shapes personal data takes, and a request that holds one is refused with no call.
 * It is deliberately blunt and cannot recognise a name; that is the caller's job. What it refuses:
 *
 * <ul>
 *   <li>a run of six or more digits (a phone, an ID or account number, a plain large amount: facts are
 *       sent in their grouped display form, {@code 1,250,000}, so they are not in this class);
 *   <li>an email address;
 *   <li>a Uganda mobile number in any spacing, with or without {@code +256}, and any {@code +} number of
 *       ten to fourteen digits, spaced or dashed or in brackets;
 *   <li>a national ID number shape ({@code CM} or {@code CF} and twelve letters or digits).
 * </ul>
 */
final class PromptScreen {

    /** What was found, as a closed word that is logged (never the text). */
    enum Finding {
        DIGITS,
        EMAIL,
        PHONE,
        ID_NUMBER
    }

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{6,}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern UGANDA_MOBILE =
            Pattern.compile("(?<!\\d)(?:\\+?256|0)[ .\\-()]*7(?:[ .\\-()]*\\d){8}(?!\\d)");
    private static final Pattern INTERNATIONAL = Pattern.compile("(?<!\\d)\\+\\d(?:[ .\\-()]*\\d){9,13}(?!\\d)");
    private static final Pattern NIN = Pattern.compile("(?<![A-Za-z0-9])[Cc][MFmf][A-Za-z0-9]{12}(?![A-Za-z0-9])");

    private PromptScreen() {}

    static Optional<Finding> check(LlmClient.Request request) {
        for (LlmClient.Message message : request.messages()) {
            Optional<Finding> found = check(message.content());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    static Optional<Finding> check(String text) {
        if (EMAIL.matcher(text).find()) {
            return Optional.of(Finding.EMAIL);
        }
        if (UGANDA_MOBILE.matcher(text).find() || INTERNATIONAL.matcher(text).find()) {
            return Optional.of(Finding.PHONE);
        }
        if (NIN.matcher(text).find()) {
            return Optional.of(Finding.ID_NUMBER);
        }
        if (DIGIT_RUN.matcher(text).find()) {
            return Optional.of(Finding.DIGITS);
        }
        return Optional.empty();
    }
}
