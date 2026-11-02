package com.rutera.core.bootstrap;

import com.rutera.common.domain.Firma;
import com.rutera.common.repository.FirmaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * The paid options of a standalone installation, which has no operator console: at every start the values set in
 * the environment ({@code RUTERA_AI_ACCESS}, {@code RUTERA_PLAN}, {@code RUTERA_VEHICLE_LIMIT}) are written to every
 * firma of the installation (normally the one created at the first start). An empty value leaves the database as it
 * is, so the same columns can also be changed with SQL. Runs after {@link BootstrapRunner}; ignored in cloud mode,
 * where the operator sets them per firma.
 */
@Component
@Order(20)
public class StandaloneLicence implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StandaloneLicence.class);

    private final FirmaRepository firmas;
    private final String mode;
    private final String aiAccess;
    private final String plan;
    private final String vehicleLimit;

    public StandaloneLicence(FirmaRepository firmas,
                             @Value("${rutera.mode:standalone}") String mode,
                             @Value("${rutera.standalone.ai-access:}") String aiAccess,
                             @Value("${rutera.standalone.plan:}") String plan,
                             @Value("${rutera.standalone.vehicle-limit:}") String vehicleLimit) {
        this.firmas = firmas;
        this.mode = mode;
        this.aiAccess = aiAccess.strip();
        this.plan = plan.strip();
        this.vehicleLimit = vehicleLimit.strip();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (aiAccess.isEmpty() && plan.isEmpty() && vehicleLimit.isEmpty()) {
            return;
        }
        if (!"standalone".equals(mode)) {
            log.warn("RUTERA_AI_ACCESS, RUTERA_PLAN and RUTERA_VEHICLE_LIMIT apply to standalone installations only");
            return;
        }
        Boolean ai = switch (aiAccess.toLowerCase(Locale.ROOT)) {
            case "" -> null;
            case "true" -> true;
            case "false" -> false;
            default -> {
                log.warn("RUTERA_AI_ACCESS must be true or false, not '{}': left unchanged", aiAccess);
                yield null;
            }
        };
        String newPlan = plan.isEmpty() ? null : plan.toUpperCase(Locale.ROOT);
        if (newPlan != null && newPlan.length() > 32) {
            log.warn("RUTERA_PLAN is longer than 32 characters: left unchanged");
            newPlan = null;
        }
        Integer limit = null;
        if (!vehicleLimit.isEmpty()) {
            try {
                limit = Integer.parseInt(vehicleLimit);
            } catch (NumberFormatException e) {
                limit = -1;
            }
            if (limit < 0) {
                log.warn("RUTERA_VEHICLE_LIMIT must be a whole number from 0: left unchanged");
                limit = null;
            }
        }
        for (Firma f : firmas.findAll()) {
            boolean changed = false;
            if (ai != null && f.isAiAccess() != ai) {
                f.setAiAccess(ai);
                changed = true;
            }
            if (newPlan != null && !newPlan.equals(f.getPlan())) {
                f.setPlan(newPlan);
                changed = true;
            }
            if (limit != null && f.getVehicleLimit() != limit) {
                f.setVehicleLimit(limit);
                changed = true;
            }
            if (changed) {
                firmas.save(f);
                log.info("Standalone licence applied to firma {} (id {}): AI access {}, plan {}, vehicle limit {}",
                        f.getName(), f.getId(), f.isAiAccess(), f.getPlan(), f.getVehicleLimit());
            }
        }
    }
}
