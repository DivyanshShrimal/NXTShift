package com.nxtshift.app.ocr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict staff allowlist and canonical identity normalization for hospital duty rosters.
 * Enforces the 13 approved canonical staff identities while providing configurable
 * approved identities (such as Yamini) and safe OCR variation normalization.
 */
public class StaffAllowlistManager {

    public static final List<String> DEFAULT_13_CANONICAL_STAFF = Collections.unmodifiableList(Arrays.asList(
            "Munesh",
            "Pradeep",
            "Vishal",
            "Ajay Jangid",
            "Govind",
            "Rohit",
            "Kuldeep",
            "Shiv",
            "Ajay Jindoliya",
            "Ram",
            "Vikas",
            "Hansraj",
            "Naveen"
    ));

    public enum MatchStatus {
        EXACT,              // Exact match to an approved canonical staff member
        NORMALIZED_OCR,     // Recognized OCR spelling variation (e.g. Yarmini -> Yamini)
        COMPOUND_SPLIT,     // Two approved staff combined without delimiter (e.g. Hansraj Naveen)
        AMBIGUOUS,          // Ambiguous prefix/token (e.g. "Ajay" matches Ajay Jangid & Ajay Jindoliya)
        UNKNOWN_SKIPPED     // Not in allowlist, non-person, or unapproved
    }

    public static class StaffResolutionResult {
        public final MatchStatus status;
        public final List<String> canonicalNames;
        public final String originalText;
        public final String note;

        public StaffResolutionResult(MatchStatus status, List<String> canonicalNames, String originalText, String note) {
            this.status = status;
            this.canonicalNames = canonicalNames != null ? canonicalNames : Collections.emptyList();
            this.originalText = originalText;
            this.note = note;
        }

        public boolean isApproved() {
            return status == MatchStatus.EXACT || status == MatchStatus.NORMALIZED_OCR || status == MatchStatus.COMPOUND_SPLIT;
        }
    }

    private static final Set<String> configuredIdentities = new LinkedHashSet<>(DEFAULT_13_CANONICAL_STAFF);
    private static boolean yaminiConfigured = true; // Configurable: true by default for current hospital roster workflow

    static {
        if (yaminiConfigured) {
            configuredIdentities.add("Yamini");
        }
    }

    public static synchronized void setYaminiEnabled(boolean enabled) {
        yaminiConfigured = enabled;
        if (enabled) {
            configuredIdentities.add("Yamini");
        } else {
            configuredIdentities.remove("Yamini");
        }
    }

    public static synchronized boolean isYaminiEnabled() {
        return yaminiConfigured;
    }

    public static synchronized void addConfiguredIdentity(String name) {
        if (name != null && !name.trim().isEmpty()) {
            configuredIdentities.add(name.trim());
        }
    }

    public static synchronized void resetToDefaultAllowlist() {
        configuredIdentities.clear();
        configuredIdentities.addAll(DEFAULT_13_CANONICAL_STAFF);
        if (yaminiConfigured) {
            configuredIdentities.add("Yamini");
        }
    }

    public static synchronized Set<String> getConfiguredIdentities() {
        return new LinkedHashSet<>(configuredIdentities);
    }

    /**
     * Resolves an OCR candidate token against the strict staff allowlist.
     */
    @NonNull
    public static StaffResolutionResult resolveCandidate(@Nullable String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return new StaffResolutionResult(MatchStatus.UNKNOWN_SKIPPED, Collections.emptyList(), rawText, "Empty text");
        }

        String cleaned = FullMonthRosterParser.cleanName(rawText);
        if (cleaned.isEmpty()) {
            return new StaffResolutionResult(MatchStatus.UNKNOWN_SKIPPED, Collections.emptyList(), rawText, "Empty text");
        }

        String norm = FullMonthRosterParser.normalizeForComparison(cleaned);

        // 1. Direct exact or normalized check against configured identities
        for (String canonical : getConfiguredIdentities()) {
            String normCanonical = FullMonthRosterParser.normalizeForComparison(canonical);
            if (norm.equals(normCanonical) || cleaned.equalsIgnoreCase(canonical)) {
                return new StaffResolutionResult(MatchStatus.EXACT, Collections.singletonList(canonical), rawText, "Exact canonical match");
            }
        }

        if (OcrContextCorrection.isNonPersonToken(cleaned) || OcrContextCorrection.isHeaderOrLegendLine(cleaned)) {
            return new StaffResolutionResult(MatchStatus.UNKNOWN_SKIPPED, Collections.emptyList(), rawText, "Non-person or header text");
        }

        // 2. Check for known OCR spelling variations
        // "Yarmini" -> "Yamini" (only if Yamini is configured)
        if (norm.equals("yarmini") || norm.equals("yarnini") || norm.equals("yamin") || norm.equals("yamni")) {
            if (isYaminiEnabled()) {
                return new StaffResolutionResult(MatchStatus.NORMALIZED_OCR, Collections.singletonList("Yamini"), rawText, "OCR normalized to Yamini");
            } else {
                return new StaffResolutionResult(MatchStatus.UNKNOWN_SKIPPED, Collections.emptyList(), rawText, "Allowlist inconsistency: Yamini is not configured");
            }
        }

        // Other safe minor OCR corrections for the 13 canonical names
        for (String canonical : DEFAULT_13_CANONICAL_STAFF) {
            String normCanonical = FullMonthRosterParser.normalizeForComparison(canonical);
            if (isSafeMinorOcrVariant(norm, normCanonical)) {
                return new StaffResolutionResult(MatchStatus.NORMALIZED_OCR, Collections.singletonList(canonical), rawText, "Minor OCR correction to " + canonical);
            }
        }

        // 3. Ambiguous Name Check: "Ajay" alone without surname
        if (norm.equals("ajay") || cleaned.equalsIgnoreCase("Ajay")) {
            return new StaffResolutionResult(MatchStatus.AMBIGUOUS, Collections.emptyList(), rawText,
                    "Ambiguous name 'Ajay': could refer to 'Ajay Jangid' or 'Ajay Jindoliya'");
        }

        // 4. Compound Name Splitting Check: e.g. "Hansraj Naveen"
        // Check if rawText can be partitioned into two distinct approved staff members
        List<String> compoundParts = detectCompoundStaffMembers(cleaned);
        if (compoundParts.size() >= 2) {
            return new StaffResolutionResult(MatchStatus.COMPOUND_SPLIT, compoundParts, rawText, "Split compound staff into distinct members");
        }

        // 5. Unknown person / Not on allowlist
        return new StaffResolutionResult(MatchStatus.UNKNOWN_SKIPPED, Collections.emptyList(), rawText, "Name not present in approved hospital staff allowlist");
    }

    /**
     * Detects if a text contains two approved staff members without a standard delimiter,
     * e.g. "Hansraj Naveen" -> ["Hansraj", "Naveen"].
     * Does NOT split single two-word names like "Ajay Jangid" or "Ajay Jindoliya".
     */
    private static List<String> detectCompoundStaffMembers(String text) {
        String[] words = text.split("\\s+");
        Set<String> approved = getConfiguredIdentities();

        // If single word, check if it's a concatenation without space of two approved identities
        if (words.length == 1) {
            String norm = FullMonthRosterParser.normalizeForComparison(text);
            for (String app1 : approved) {
                String norm1 = FullMonthRosterParser.normalizeForComparison(app1);
                if (norm.startsWith(norm1) && norm.length() > norm1.length()) {
                    String remainder = norm.substring(norm1.length());
                    for (String app2 : approved) {
                        String norm2 = FullMonthRosterParser.normalizeForComparison(app2);
                        if (remainder.equals(norm2)) {
                            return Arrays.asList(app1, app2);
                        }
                    }
                }
            }
            return Collections.emptyList();
        }

        // If the entire text matches an approved two-word name (e.g. "Ajay Jangid"), do NOT split!
        for (String canonical : approved) {
            if (canonical.equalsIgnoreCase(text) ||
                    FullMonthRosterParser.normalizeForComparison(canonical).equals(FullMonthRosterParser.normalizeForComparison(text))) {
                return Collections.emptyList();
            }
        }

        List<String> matched = new ArrayList<>();

        for (int i = 0; i < words.length; i++) {
            String w = words[i];

            // Check two-word combo first (e.g. "Ajay Jangid", "Ajay Jindoliya")
            if (i + 1 < words.length) {
                String twoWord = w + " " + words[i + 1];
                String matchTwo = findDirectMatch(twoWord, approved);
                if (matchTwo != null) {
                    matched.add(matchTwo);
                    i++; // skip next word
                    continue;
                }
            }

            // Check single word
            String match1 = findDirectMatch(w, approved);
            if (match1 != null) {
                matched.add(match1);
            }
        }

        if (matched.size() >= 2) {
            return matched;
        }

        return Collections.emptyList();
    }

    private static String findDirectMatch(String candidate, Set<String> approved) {
        String norm = FullMonthRosterParser.normalizeForComparison(candidate);
        if (norm.equals("ajay")) {
            return null; // Ambiguous, don't auto-resolve
        }
        for (String app : approved) {
            if (FullMonthRosterParser.normalizeForComparison(app).equals(norm)) {
                return app;
            }
        }
        // Known OCR spelling variations: Yarmini/Yamin -> Yamini (only when Yamini is enabled)
        if ((norm.equals("yarmini") || norm.equals("yarnini") || norm.equals("yamin") || norm.equals("yamni")) && isYaminiEnabled()) {
            return "Yamini";
        }
        // Safe minor OCR corrections for the canonical identities
        for (String canonical : DEFAULT_13_CANONICAL_STAFF) {
            String normCanonical = FullMonthRosterParser.normalizeForComparison(canonical);
            if (isSafeMinorOcrVariant(norm, normCanonical)) {
                return canonical;
            }
        }
        return null;
    }

    /**
     * Determines whether two normalized strings are safe minor OCR variations
     * (e.g. single character substitution of known OCR confusions like rn->m, l->i, vv->w),
     * strictly avoiding merging distinct names like Anita/Sunita or Ajay Jangid/Ajay Jindoliya.
     */
    private static boolean isSafeMinorOcrVariant(String ocrNorm, String targetNorm) {
        if (ocrNorm.length() < 4 || targetNorm.length() < 4) return false;
        // Never match if lengths differ by more than 1
        if (Math.abs(ocrNorm.length() - targetNorm.length()) > 1) return false;

        // Check common OCR ligature confusions: 'rn' misread as 'm' or vice versa
        String expandedOcr = ocrNorm.replace("rn", "m").replace("vv", "w").replace("cl", "d");
        String expandedTarget = targetNorm.replace("rn", "m").replace("vv", "w").replace("cl", "d");
        if (expandedOcr.equals(expandedTarget)) return true;

        // Levenshtein distance 1 only if length >= 5 and characters are not leading
        if (ocrNorm.length() >= 5 && targetNorm.length() >= 5) {
            if (levenshteinDistance(ocrNorm, targetNorm) == 1 && ocrNorm.charAt(0) == targetNorm.charAt(0)) {
                // Do not merge similar allowlist members with each other
                for (String other : DEFAULT_13_CANONICAL_STAFF) {
                    String otherNorm = FullMonthRosterParser.normalizeForComparison(other);
                    if (!otherNorm.equals(targetNorm) && levenshteinDistance(ocrNorm, otherNorm) <= 1) {
                        return false; // Ambiguous between two allowlist members
                    }
                }
                return true;
            }
        }

        return false;
    }

    private static int levenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) costs[j] = j;
                else {
                    if (j > 0) {
                        int newValue = costs[j - 1];
                        if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                        }
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) costs[s2.length()] = lastValue;
        }
        return costs[s2.length()];
    }
}
