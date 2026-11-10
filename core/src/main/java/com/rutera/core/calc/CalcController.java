package com.rutera.core.calc;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Trip calculator and toll tariffs.
 * <pre>
 * POST   /app/calc/trip               {start, vehicleId?, driverIds?, crewSize?, driverState?, manualState?, legs
 *                                      or waypoints, options?} -> Plan                    (trip.trip/view)
 * POST   /app/calc/route              {points, vehicleId?, profile?, avoidTolls?, avoidFerries?} -> Route (trip.trip/view)
 * GET    /app/calc/places?q=&limit=   -> Place[] for search-as-you-type                    (trip.trip/view)
 * GET    /app/calc/toll-tariffs       -> TollTariff[]                                     (trip.trip/view)
 * GET    /app/calc/fuel-prices        -> FuelPrice[] (shared by all firmas)               (trip.trip/view)
 * POST   /app/calc/fuel-prices        {country, product, priceNet, currency, validOn, source?} -> FuelPrice (operator)
 * DELETE /app/calc/fuel-prices/{id}   -> 204                                              (operator)
 * POST   /app/calc/toll-tariffs       {input} -> 201 TollTariff                           (trip.trip/manage)
 * PUT    /app/calc/toll-tariffs/{id}  {input} -> TollTariff                               (trip.trip/manage)
 * DELETE /app/calc/toll-tariffs/{id}  -> 204                                              (trip.trip/manage)
 * </pre>
 */
@RestController
public class CalcController {

    private final TripCalcService calc;
    private final TollTariffService tariffs;
    private final RouteService routes;
    private final PlaceSearch places;
    private final FuelPriceService fuelPrices;

    public CalcController(TripCalcService calc, TollTariffService tariffs, RouteService routes, PlaceSearch places,
                          FuelPriceService fuelPrices) {
        this.calc = calc;
        this.tariffs = tariffs;
        this.routes = routes;
        this.places = places;
        this.fuelPrices = fuelPrices;
    }

    @GetMapping({"/app/calc/fuel-prices", "/api/v1/calc/fuel-prices"})
    public List<CalcDto.FuelPriceDto> fuelPrices() {
        return fuelPrices.list();
    }

    @PostMapping("/app/calc/fuel-prices")
    public CalcDto.FuelPriceDto saveFuelPrice(@Valid @RequestBody CalcDto.FuelPriceInput input) {
        return fuelPrices.save(input);
    }

    @DeleteMapping("/app/calc/fuel-prices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFuelPrice(@PathVariable Long id) {
        fuelPrices.delete(id);
    }

    @GetMapping({"/app/calc/places", "/api/v1/calc/places"})
    public List<CalcDto.Place> places(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) Integer limit) {
        return places.search(q, limit);
    }

    @PostMapping({"/app/calc/route", "/api/v1/calc/route"})
    public CalcDto.Route route(@Valid @RequestBody CalcDto.RouteInput input) {
        CalcDto.Route route = routes.plan(input);
        return route.withBreakdown(calc.breakdown(input, route));
    }

    @PostMapping({"/app/calc/trip", "/api/v1/calc/trip"})
    public CalcDto.Plan plan(@Valid @RequestBody CalcDto.TripInput input) {
        return calc.plan(input);
    }

    @GetMapping({"/app/calc/toll-tariffs", "/api/v1/calc/toll-tariffs"})
    public List<CalcDto.TollTariff> tariffs() {
        return tariffs.list();
    }

    @PostMapping({"/app/calc/toll-tariffs", "/api/v1/calc/toll-tariffs"})
    @ResponseStatus(HttpStatus.CREATED)
    public CalcDto.TollTariff create(@Valid @RequestBody CalcDto.TollTariffInput input) {
        return tariffs.create(input);
    }

    @PutMapping({"/app/calc/toll-tariffs/{id}", "/api/v1/calc/toll-tariffs/{id}"})
    public CalcDto.TollTariff update(@PathVariable Long id, @Valid @RequestBody CalcDto.TollTariffInput input) {
        return tariffs.update(id, input);
    }

    @DeleteMapping({"/app/calc/toll-tariffs/{id}", "/api/v1/calc/toll-tariffs/{id}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        tariffs.delete(id);
    }
}
