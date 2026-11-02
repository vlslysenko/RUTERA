package com.rutera.core.calc;

import com.rutera.common.domain.TollTariff;
import com.rutera.common.domain.Vehicle;
import com.rutera.common.repository.TollTariffRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.fuel.read.FuelValues;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The firma's toll tariffs for the trip calculator ({@code /app/calc/toll-tariffs}). */
@Service
public class TollTariffService {

    public static final String MODULE = "calc";

    private final TollTariffRepository tariffs;
    private final AuditService audit;

    public TollTariffService(TollTariffRepository tariffs, AuditService audit) {
        this.tariffs = tariffs;
        this.audit = audit;
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_VIEW)
    @Transactional(readOnly = true)
    public List<CalcDto.TollTariff> list() {
        return tariffs.findAllByOrderByCountryAscValidFromDescIdAsc().stream().map(TollTariffService::toDto).toList();
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_MANAGE)
    @Transactional
    public CalcDto.TollTariff create(CalcDto.TollTariffInput input) {
        TollTariff t = new TollTariff();
        t.setFirmaId(InputFields.requireFirmaId());
        save(t, input);
        audit.record(AuditEvent.of(MODULE, "toll_tariff_created", "Added a toll tariff for " + t.getCountry())
                .entity("toll_tariff", t.getId()));
        return toDto(t);
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_MANAGE)
    @Transactional
    public CalcDto.TollTariff update(Long id, CalcDto.TollTariffInput input) {
        TollTariff t = load(id);
        save(t, input);
        audit.record(AuditEvent.of(MODULE, "toll_tariff_updated", "Updated a toll tariff for " + t.getCountry())
                .entity("toll_tariff", t.getId()));
        return toDto(t);
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_MANAGE)
    @Transactional
    public void delete(Long id) {
        TollTariff t = load(id);
        tariffs.delete(t);
        audit.record(AuditEvent.of(MODULE, "toll_tariff_deleted", "Deleted a toll tariff for " + t.getCountry())
                .entity("toll_tariff", t.getId()));
    }

    private void save(TollTariff t, CalcDto.TollTariffInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        String country = InputFields.country("country", in.country(), errors);
        if (country == null && !errors.containsKey("country")) {
            errors.put("country", ErrorCodes.FIELD_REQUIRED);
        }
        Vehicle.EuroClass euro = InputFields.parseEnum(Vehicle.EuroClass.class, "euroClass", in.euroClass(), false,
                errors);
        if (in.axlesMin() != null && (in.axlesMin() < 1 || in.axlesMin() > 10)) {
            errors.put("axlesMin", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (in.weightMinKg() != null && (in.weightMinKg() < 0 || in.weightMinKg() > 100_000)) {
            errors.put("weightMinKg", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (in.ratePerKm() == null) {
            errors.put("ratePerKm", ErrorCodes.FIELD_REQUIRED);
        }
        String currency = in.currency() == null ? null : FuelValues.currency(in.currency());
        if (in.currency() == null || in.currency().isBlank()) {
            errors.put("currency", ErrorCodes.FIELD_REQUIRED);
        } else if (currency == null) {
            errors.put("currency", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        t.setCountry(country);
        t.setEuroClass(euro);
        t.setAxlesMin(in.axlesMin());
        t.setWeightMinKg(in.weightMinKg());
        t.setRatePerKm(in.ratePerKm().setScale(4, RoundingMode.HALF_UP));
        t.setCurrency(currency);
        t.setValidFrom(in.validFrom());
        t.setNotes(InputFields.blankToNull(in.notes()));
        tariffs.saveAndFlush(t);
    }

    private TollTariff load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return tariffs.findById(id).filter(t -> firmaId.equals(t.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private static CalcDto.TollTariff toDto(TollTariff t) {
        return new CalcDto.TollTariff(t.getId(), t.getCountry(), t.getEuroClass(), t.getAxlesMin(),
                t.getWeightMinKg(), t.getRatePerKm(), t.getCurrency(), t.getValidFrom(), t.getNotes(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
