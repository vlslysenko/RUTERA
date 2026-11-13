package com.rutera.core.explain;

import com.rutera.common.domain.DriverExplanation.Reason;
import com.rutera.common.domain.DriverViolation;
import com.rutera.core.tacho.hos.HosEngine;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the research on driver explanations (research/driver-explanations, 2026-11-13) says about infringements and
 * reasons: the template's infringement code of each violation rule, which infringements and reasons allow an
 * Article 12 claim of Regulation 561/2006 (first paragraph: reaching a suitable stopping place for safety; second
 * and third: up to 1 h / 2 h more driving to reach the base or home for a weekly rest), which reasons need the
 * driver's own text, and the codes of the first version kept as aliases.
 */
public final class ExplanationCatalog {

    /** Template infringement codes used by RUTERA's rules, with their Article 12 options. */
    public enum Infringement {
        DAILY_DRIVING_EXCEEDED(true, true), WEEKLY_DRIVING_EXCEEDED(true, true),
        FORTNIGHTLY_DRIVING_EXCEEDED(true, false), CONTINUOUS_DRIVING_EXCEEDED(true, false),
        DAILY_REST_INSUFFICIENT(true, false), WEEKLY_REST_INSUFFICIENT(true, false);

        final boolean safety;
        final boolean home;

        Infringement(boolean safety, boolean home) {
            this.safety = safety;
            this.home = home;
        }
    }

    /** Reasons that may carry the Article 12 first paragraph claim (the Commission's examples and safety cases). */
    private static final Set<Reason> SAFETY_REASONS = EnumSet.of(Reason.TRAFFIC_ACCIDENT, Reason.ROAD_CLOSURE_DETOUR,
            Reason.EXTREME_WEATHER, Reason.NO_PARKING_SPACE, Reason.SECURITY_THREAT, Reason.VEHICLE_BREAKDOWN,
            Reason.LOAD_SAFETY, Reason.MEDICAL_EMERGENCY, Reason.FERRY_TRAIN_DELAY);
    /** Reasons whose explanation needs the driver's own words. */
    private static final Set<Reason> TEXT_REQUIRED = EnumSet.of(Reason.NO_PARKING_SPACE, Reason.DATA_DISPUTE,
            Reason.OTHER);
    /** Codes of the first version (cd005a4), accepted on input. */
    private static final Map<String, Reason> ALIASES = Map.of(
            "SAFE_STOPPING_PLACE", Reason.NO_PARKING_SPACE, "SAFETY", Reason.SECURITY_THREAT,
            "RETURN_FOR_WEEKLY_REST", Reason.REACH_HOME_OR_BASE, "NO_PARKING", Reason.NO_PARKING_SPACE,
            "ACCIDENT", Reason.TRAFFIC_ACCIDENT, "ROAD_CLOSURE", Reason.ROAD_CLOSURE_DETOUR,
            "BREAKDOWN", Reason.VEHICLE_BREAKDOWN, "WEATHER", Reason.EXTREME_WEATHER);

    private ExplanationCatalog() {
    }

    public static Infringement infringement(String ruleCode) {
        if (ruleCode == null) {
            return null;
        }
        return switch (DriverViolation.Rule.valueOf(ruleCode)) {
            case DAILY_DRIVING_9H, DAILY_DRIVING_10H -> Infringement.DAILY_DRIVING_EXCEEDED;
            case WEEKLY_DRIVING_56H -> Infringement.WEEKLY_DRIVING_EXCEEDED;
            case TWO_WEEK_DRIVING_90H -> Infringement.FORTNIGHTLY_DRIVING_EXCEEDED;
            case BREAK_45_AFTER_4H30 -> Infringement.CONTINUOUS_DRIVING_EXCEEDED;
            case DAILY_REST_11H, DAILY_REST_9H -> Infringement.DAILY_REST_INSUFFICIENT;
            case WEEKLY_REST_45H, WEEKLY_REST_24H -> Infringement.WEEKLY_REST_INSUFFICIENT;
        };
    }

    /** The rule's limit in minutes, as the HOS engine checks it. */
    public static int limitMin(DriverViolation.Rule rule) {
        return switch (rule) {
            case DAILY_DRIVING_9H -> HosEngine.DAILY_DRIVING;
            case DAILY_DRIVING_10H -> HosEngine.DAILY_DRIVING_EXTENDED;
            case WEEKLY_DRIVING_56H -> HosEngine.WEEKLY_DRIVING;
            case TWO_WEEK_DRIVING_90H -> HosEngine.TWO_WEEK_DRIVING;
            case BREAK_45_AFTER_4H30 -> HosEngine.BREAK_AFTER;
            case DAILY_REST_11H -> HosEngine.DAILY_REST;
            case DAILY_REST_9H -> HosEngine.DAILY_REST_REDUCED;
            case WEEKLY_REST_45H -> HosEngine.WEEKLY_REST;
            case WEEKLY_REST_24H -> HosEngine.WEEKLY_REST_REDUCED;
        };
    }

    /** Whether the rule's limit is a minimum (rests) rather than a maximum (driving). */
    public static boolean minimum(DriverViolation.Rule rule) {
        return switch (rule) {
            case DAILY_REST_11H, DAILY_REST_9H, WEEKLY_REST_45H, WEEKLY_REST_24H -> true;
            default -> false;
        };
    }

    /** A reason code, or one of the first version's codes. */
    public static Optional<Reason> reason(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String c = code.strip().toUpperCase(Locale.ROOT);
        Reason alias = ALIASES.get(c);
        if (alias != null) {
            return Optional.of(alias);
        }
        try {
            return Optional.of(Reason.valueOf(c));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Whether the code is one of the first version's (its reasons did not ask for the driver's text). */
    public static boolean isAlias(String code) {
        return code != null && ALIASES.containsKey(code.strip().toUpperCase(Locale.ROOT));
    }

    public static boolean safetyAllowed(Infringement inf, Reason reason) {
        return inf != null && inf.safety && (reason == null || SAFETY_REASONS.contains(reason));
    }

    public static boolean homeAllowed(Infringement inf, Reason reason) {
        return inf != null && inf.home && (reason == null || reason == Reason.REACH_HOME_OR_BASE);
    }

    /** Whether the reason may carry the first paragraph claim for some infringement. */
    public static boolean safetyReason(Reason reason) {
        return SAFETY_REASONS.contains(reason);
    }

    /** Whether the reason may carry the second or third paragraph claim for some infringement. */
    public static boolean homeReason(Reason reason) {
        return reason == Reason.REACH_HOME_OR_BASE;
    }

    public static boolean textRequired(Reason reason) {
        return TEXT_REQUIRED.contains(reason);
    }
}
