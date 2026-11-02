package com.rutera.core.driver;

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

import java.time.LocalDate;
import java.util.List;

/**
 * Driver HR.
 * <pre>
 * GET    /app/drivers/{id}/absences?from&to          -> Absence[]                 (driver.driver/view)
 * POST   /app/drivers/{id}/absences                  -> 201 Absence               (driver.absence/manage)
 * PUT    /app/drivers/{id}/absences/{absenceId}      -> Absence                   (driver.absence/manage)
 * DELETE /app/drivers/{id}/absences/{absenceId}      -> 204                       (driver.absence/manage)
 * GET    /app/absences?from&to&kind&driverId         -> Absence[] of the firma    (driver.driver/view)
 * GET    /app/drivers/{id}/days?month=YYYY-MM        -> {days, summary}           (driver.driver/view)
 * PUT    /app/drivers/{id}/days/{date}  {kind, note} -> Day (manual entry)       (driver.driver/manage)
 * DELETE /app/drivers/{id}/days/{date}               -> Day (computed again)      (driver.driver/manage)
 * GET    /app/drivers/{id}/pay-rates                 -> PayRate[]                 (driver.payment/view)
 * POST / PUT /{rateId} / DELETE /{rateId}            -> PayRate / 204             (driver.payment/manage)
 * GET    /app/drivers/{id}/payments?from&to          -> Payment[]                 (driver.payment/view)
 * POST / PUT /{paymentId} / DELETE /{paymentId}      -> Payment / 204             (driver.payment/manage)
 * GET    /app/payments?period&driverId&kind          -> {items, totals}           (driver.payment/view)
 * </pre>
 */
@RestController
public class DriverHrController {

    private final DriverHrService service;

    public DriverHrController(DriverHrService service) {
        this.service = service;
    }

    // --- absences

    @GetMapping("/app/drivers/{id}/absences")
    public List<HrDto.Absence> absences(@PathVariable Long id, @RequestParam(required = false) LocalDate from,
                                        @RequestParam(required = false) LocalDate to) {
        return service.absences(id, from, to);
    }

    @PostMapping("/app/drivers/{id}/absences")
    @ResponseStatus(HttpStatus.CREATED)
    public HrDto.Absence createAbsence(@PathVariable Long id, @Valid @RequestBody HrDto.AbsenceInput input) {
        return service.createAbsence(id, input);
    }

    @PutMapping("/app/drivers/{id}/absences/{absenceId}")
    public HrDto.Absence updateAbsence(@PathVariable Long id, @PathVariable Long absenceId,
                                       @Valid @RequestBody HrDto.AbsenceInput input) {
        return service.updateAbsence(id, absenceId, input);
    }

    @DeleteMapping("/app/drivers/{id}/absences/{absenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAbsence(@PathVariable Long id, @PathVariable Long absenceId) {
        service.deleteAbsence(id, absenceId);
    }

    @GetMapping("/app/absences")
    public List<HrDto.Absence> allAbsences(@RequestParam(required = false) LocalDate from,
                                           @RequestParam(required = false) LocalDate to,
                                           @RequestParam(required = false) String kind,
                                           @RequestParam(required = false) Long driverId) {
        return service.allAbsences(from, to, kind, driverId);
    }

    // --- working days

    @GetMapping("/app/drivers/{id}/days")
    public HrDto.Month month(@PathVariable Long id, @RequestParam(required = false) String month) {
        return service.month(id, month);
    }

    @PutMapping("/app/drivers/{id}/days/{day}")
    public HrDto.Day setDay(@PathVariable Long id, @PathVariable LocalDate day,
                            @Valid @RequestBody HrDto.DayInput input) {
        return service.setDay(id, day, input);
    }

    @DeleteMapping("/app/drivers/{id}/days/{day}")
    public HrDto.Day clearDay(@PathVariable Long id, @PathVariable LocalDate day) {
        return service.clearDay(id, day);
    }

    // --- pay rates

    @GetMapping("/app/drivers/{id}/pay-rates")
    public List<HrDto.PayRate> rates(@PathVariable Long id) {
        return service.rates(id);
    }

    @PostMapping("/app/drivers/{id}/pay-rates")
    @ResponseStatus(HttpStatus.CREATED)
    public HrDto.PayRate createRate(@PathVariable Long id, @Valid @RequestBody HrDto.PayRateInput input) {
        return service.createRate(id, input);
    }

    @PutMapping("/app/drivers/{id}/pay-rates/{rateId}")
    public HrDto.PayRate updateRate(@PathVariable Long id, @PathVariable Long rateId,
                                    @Valid @RequestBody HrDto.PayRateInput input) {
        return service.updateRate(id, rateId, input);
    }

    @DeleteMapping("/app/drivers/{id}/pay-rates/{rateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRate(@PathVariable Long id, @PathVariable Long rateId) {
        service.deleteRate(id, rateId);
    }

    // --- payments

    @GetMapping("/app/drivers/{id}/payments")
    public List<HrDto.Payment> payments(@PathVariable Long id, @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        return service.payments(id, from, to);
    }

    @PostMapping("/app/drivers/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public HrDto.Payment createPayment(@PathVariable Long id, @Valid @RequestBody HrDto.PaymentInput input) {
        return service.createPayment(id, input);
    }

    @PutMapping("/app/drivers/{id}/payments/{paymentId}")
    public HrDto.Payment updatePayment(@PathVariable Long id, @PathVariable Long paymentId,
                                       @Valid @RequestBody HrDto.PaymentInput input) {
        return service.updatePayment(id, paymentId, input);
    }

    @DeleteMapping("/app/drivers/{id}/payments/{paymentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePayment(@PathVariable Long id, @PathVariable Long paymentId) {
        service.deletePayment(id, paymentId);
    }

    @GetMapping("/app/payments")
    public HrDto.PaymentList allPayments(@RequestParam(required = false) String period,
                                         @RequestParam(required = false) Long driverId,
                                         @RequestParam(required = false) String kind) {
        return service.allPayments(period, driverId, kind);
    }
}
