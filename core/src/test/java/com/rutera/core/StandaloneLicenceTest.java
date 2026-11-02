package com.rutera.core;

import com.rutera.common.domain.Firma;
import com.rutera.core.bootstrap.StandaloneLicence;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paid options of a standalone installation from the environment: written to every firma when set, nothing
 * changes when empty, invalid or in cloud mode. The firmas of the shared test database are restored afterwards.
 */
class StandaloneLicenceTest extends AbstractIntegrationTest {

    private record Options(boolean aiAccess, String plan, int vehicleLimit) {
    }

    private Map<Long, Options> snapshot() {
        Map<Long, Options> all = new HashMap<>();
        for (Firma f : firmas.findAll()) {
            all.put(f.getId(), new Options(f.isAiAccess(), f.getPlan(), f.getVehicleLimit()));
        }
        return all;
    }

    @Test
    void environmentValuesAreWrittenToEveryFirma() {
        Map<Long, Options> before = snapshot();
        try {
            new StandaloneLicence(firmas, "standalone", " True ", "pro", "25").run(null);
            List<Options> applied = List.copyOf(snapshot().values());
            assertThat(applied).isNotEmpty().allMatch(o -> o.aiAccess() && o.plan().equals("PRO")
                    && o.vehicleLimit() == 25);

            new StandaloneLicence(firmas, "standalone", "", "", "").run(null);
            new StandaloneLicence(firmas, "cloud", "false", "BASIC", "5").run(null);
            new StandaloneLicence(firmas, "standalone", "maybe", "", "-3").run(null);
            assertThat(List.copyOf(snapshot().values())).as("empty, cloud and invalid values change nothing")
                    .allMatch(o -> o.aiAccess() && o.plan().equals("PRO") && o.vehicleLimit() == 25);

            new StandaloneLicence(firmas, "standalone", "false", "", "").run(null);
            assertThat(snapshot().values()).allMatch(o -> !o.aiAccess() && o.plan().equals("PRO"));
        } finally {
            before.forEach((id, o) -> firmas.findById(id).ifPresent(f -> {
                f.setAiAccess(o.aiAccess());
                f.setPlan(o.plan());
                f.setVehicleLimit(o.vehicleLimit());
                firmas.save(f);
            }));
        }
    }
}
