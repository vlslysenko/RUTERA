package com.rutera.core.explain;

import com.rutera.common.domain.FirmaSetting;
import com.rutera.common.repository.FirmaSettingRepository;
import org.springframework.stereotype.Component;

/**
 * The firma's settings of driver explanations ({@code firma_setting}): whether the import asks for one for every new
 * infringement ({@code explanations.auto}, default on) and how far back an infringement may lie to get one
 * ({@code explanations.days}, default 28, the period a roadside check looks at). Read in the firma's context.
 */
@Component
public class ExplanationSettings {

    static final String AUTO = "explanations.auto";
    static final String DAYS = "explanations.days";
    public static final int DEFAULT_DAYS = 28;
    public static final int MAX_DAYS = 365;

    private final FirmaSettingRepository settings;

    public ExplanationSettings(FirmaSettingRepository settings) {
        this.settings = settings;
    }

    public boolean auto() {
        return settings.findByKey(AUTO).map(s -> !"false".equals(s.getValue().strip())).orElse(true);
    }

    public int days() {
        return settings.findByKey(DAYS).map(s -> {
            try {
                return Math.clamp(Integer.parseInt(s.getValue().strip()), 1, MAX_DAYS);
            } catch (NumberFormatException e) {
                return DEFAULT_DAYS;
            }
        }).orElse(DEFAULT_DAYS);
    }

    /** Stores both settings of the firma (inside a transaction). */
    public void save(Long firmaId, boolean auto, int days) {
        put(firmaId, AUTO, Boolean.toString(auto));
        put(firmaId, DAYS, Integer.toString(Math.clamp(days, 1, MAX_DAYS)));
    }

    private void put(Long firmaId, String key, String json) {
        FirmaSetting s = settings.findByKey(key).orElseGet(FirmaSetting::new);
        s.setFirmaId(firmaId);
        s.setKey(key);
        s.setValue(json);
        settings.save(s);
    }
}
