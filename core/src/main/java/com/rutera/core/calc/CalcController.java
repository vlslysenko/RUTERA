package com.rutera.core.calc;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Trip calculator and toll tariffs.
 * <pre>
 * POST   /app/calc/trip               {start, vehicleId?, driverIds?, crewSize?, driverState?, manualState?, legs,
 *                                      options?} -> Plan                                  (trip.trip/view)
 * GET    /app/calc/toll-tariffs       -> TollTariff[]                                     (trip.trip/view)
 * POST   /app/calc/toll-tariffs       {input} -> 201 TollTariff                           (trip.trip/manage)
 * PUT    /app/calc/toll-tariffs/{id}  {input} -> TollTariff                               (trip.trip/manage)
 * DELETE /app/calc/toll-tariffs/{id}  -> 204                                              (trip.trip/manage)
 * </pre>
 */
@RestController
public class CalcController {

    private final TripCalcService calc;
    private final TollTariffService tariffs;

    public CalcController(TripCalcService calc, TollTariffService tariffs) {
        this.calc = calc;
        this.tariffs = tariffs;
    }

    @PostMapping("/app/calc/trip")
    public CalcDto.Plan plan(@Valid @RequestBody CalcDto.TripInput input) {
        return calc.plan(input);
    }

    @GetMapping("/app/calc/toll-tariffs")
    public List<CalcDto.TollTariff> tariffs() {
        return tariffs.list();
    }

    @PostMapping("/app/calc/toll-tariffs")
    @ResponseStatus(HttpStatus.CREATED)
    public CalcDto.TollTariff create(@Valid @RequestBody CalcDto.TollTariffInput input) {
        return tariffs.create(input);
    }

    @PutMapping("/app/calc/toll-tariffs/{id}")
    public CalcDto.TollTariff update(@PathVariable Long id, @Valid @RequestBody CalcDto.TollTariffInput input) {
        return tariffs.update(id, input);
    }

    @DeleteMapping("/app/calc/toll-tariffs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tariffs.delete(id);
    }
}
