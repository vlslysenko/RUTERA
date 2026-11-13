package com.rutera.core.admin;

import com.rutera.core.explain.ExplanationSettings;
import com.rutera.core.explain.ExplanationTemplates;
import com.rutera.common.domain.Firma;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The firma's own settings ({@code /app/admin/firma}): name, address, VAT id, e-mail, language, time zone, base
 * currency and whether drivers see their pay. Plan, vehicle limit and the AI access add-on are read-only here
 * (they belong to billing).
 */
@Service
public class FirmaSettingsService {

    public static final String MODULE = "admin";
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final FirmaRepository firmas;
    private final AuditService audit;

    private final ExplanationSettings explanationSettings;
    private final ExplanationTemplates explanationTemplates;

    public FirmaSettingsService(FirmaRepository firmas, AuditService audit, ExplanationSettings explanationSettings,
                                ExplanationTemplates explanationTemplates) {
        this.explanationSettings = explanationSettings;
        this.explanationTemplates = explanationTemplates;
        this.firmas = firmas;
        this.audit = audit;
    }

    /**
     * The settings; country is the country of registration, explanationLang the language of drivers' explanations
     * that follows from it (English while there is no template for the country's language).
     */
    public record Settings(Long id, String name, String shortCode, String country, String vatId, String address,
                           String email, String lang, String timezone, String baseCurrency, String plan,
                           int vehicleLimit, boolean aiAccess, boolean driversSeePay, String explanationLang,
                           boolean explanationsAuto, int explanationsDays) {
    }

    /**
     * Body of {@code PUT /app/admin/firma}: a full replacement of the editable settings; country, explanationsAuto
     * and explanationsDays are kept when left out.
     */
    public record SettingsInput(@Size(max = 200) String name, @Size(max = 32) String vatId,
                                @Size(max = 500) String address, @Size(max = 254) String email, String lang,
                                String timezone, String baseCurrency, Boolean driversSeePay, String country,
                                Boolean explanationsAuto, Integer explanationsDays) {
    }

    @RequiresPrivilege(Privileges.ADMIN_FIRMA_MANAGE)
    @Transactional(readOnly = true)
    public Settings get() {
        return toDto(load());
    }

    @RequiresPrivilege(Privileges.ADMIN_FIRMA_MANAGE)
    @Transactional
    public Settings update(SettingsInput in) {
        Firma f = load();
        Map<String, String> errors = new LinkedHashMap<>();
        String name = InputFields.blankToNull(in.name());
        if (name == null) {
            errors.put("name", ErrorCodes.FIELD_REQUIRED);
        }
        String email = InputFields.blankToNull(in.email());
        if (email != null && !EMAIL.matcher(email).matches()) {
            errors.put("email", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        String lang = InputFields.blankToNull(in.lang());
        if (lang == null) {
            errors.put("lang", ErrorCodes.FIELD_REQUIRED);
        } else if (!SupportedLanguages.isSupported(lang.toLowerCase(Locale.ROOT))) {
            errors.put("lang", ErrorCodes.FIELD_INVALID_VALUE);
        }
        String zone = InputFields.blankToNull(in.timezone());
        if (zone == null) {
            errors.put("timezone", ErrorCodes.FIELD_REQUIRED);
        } else {
            try {
                ZoneId.of(zone);
            } catch (DateTimeException e) {
                errors.put("timezone", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        String currency = in.baseCurrency() == null ? null : in.baseCurrency().strip().toUpperCase(Locale.ROOT);
        if (currency == null || currency.isEmpty()) {
            errors.put("baseCurrency", ErrorCodes.FIELD_REQUIRED);
        } else if (!CURRENCY.matcher(currency).matches()) {
            errors.put("baseCurrency", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        String country = in.country() == null ? f.getCountry() : InputFields.country("country", in.country(), errors);
        if (in.country() != null && country == null && !errors.containsKey("country")) {
            errors.put("country", ErrorCodes.FIELD_REQUIRED);
        }
        if (in.explanationsDays() != null && (in.explanationsDays() < 1
                || in.explanationsDays() > ExplanationSettings.MAX_DAYS)) {
            errors.put("explanationsDays", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        f.setCountry(country);
        explanationSettings.save(f.getId(), in.explanationsAuto() == null ? explanationSettings.auto()
                : in.explanationsAuto(), in.explanationsDays() == null ? explanationSettings.days()
                : in.explanationsDays());
        f.setName(name);
        f.setVatId(InputFields.blankToNull(in.vatId()));
        f.setAddress(InputFields.blankToNull(in.address()));
        f.setEmail(email);
        f.setLang(lang.toLowerCase(Locale.ROOT));
        f.setTimezone(zone);
        f.setBaseCurrency(currency);
        f.setDriversSeePay(Boolean.TRUE.equals(in.driversSeePay()));
        firmas.save(f);
        audit.record(AuditEvent.of(MODULE, "firma_settings_updated", "Updated the company settings")
                .entity("firma", f.getId()).detail("driversSeePay", f.isDriversSeePay())
                .detail("country", f.getCountry()));
        return toDto(f);
    }

    private Firma load() {
        return firmas.findById(InputFields.requireFirmaId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private Settings toDto(Firma f) {
        return new Settings(f.getId(), f.getName(), f.getShortCode(), f.getCountry(), f.getVatId(), f.getAddress(),
                f.getEmail(), f.getLang(), f.getTimezone(), f.getBaseCurrency(), f.getPlan(), f.getVehicleLimit(),
                f.isAiAccess(), f.isDriversSeePay(), explanationTemplates.documentLanguage(f.getCountry()),
                explanationSettings.auto(), explanationSettings.days());
    }
}
