package com.rutera.core.calc;

import com.rutera.common.domain.FuelPrice;
import com.rutera.common.domain.FuelTransaction;
import com.rutera.common.repository.FuelPriceRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.common.security.Role;
import com.rutera.core.security.AuthenticatedUser;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared fuel price table ({@code /app/calc/fuel-prices}): everyone with the calculator reads it, only the
 * operator (SUPERUSER) writes it, because it is the same for all firmas. A row per country, product and day.
 */
@Service
public class FuelPriceService {

    static final Set<FuelTransaction.Product> PRODUCTS = EnumSet.of(FuelTransaction.Product.DIESEL,
            FuelTransaction.Product.ADBLUE, FuelTransaction.Product.PETROL, FuelTransaction.Product.LNG,
            FuelTransaction.Product.CNG);

    private final FuelPriceRepository prices;

    public FuelPriceService(FuelPriceRepository prices) {
        this.prices = prices;
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_VIEW)
    @Transactional(readOnly = true)
    public List<CalcDto.FuelPriceDto> list() {
        return prices.findAllByOrderByCountryAscProductAscValidOnDesc().stream().map(CalcDto.FuelPriceDto::of)
                .toList();
    }

    /** Creates the row of the country, product and day, or replaces its price. */
    @Transactional
    public CalcDto.FuelPriceDto save(CalcDto.FuelPriceInput in) {
        requireOperator();
        Map<String, String> errors = new LinkedHashMap<>();
        String country = InputFields.country("country", in.country(), errors);
        if (country == null && !errors.containsKey("country")) {
            errors.put("country", ErrorCodes.FIELD_REQUIRED);
        }
        FuelTransaction.Product product = InputFields.parseEnum(FuelTransaction.Product.class, "product",
                in.product(), true, errors);
        if (product != null && !PRODUCTS.contains(product)) {
            errors.put("product", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (in.priceNet() == null) {
            errors.put("priceNet", ErrorCodes.FIELD_REQUIRED);
        } else if (in.priceNet().signum() <= 0 || in.priceNet().compareTo(new BigDecimal("99999")) > 0) {
            errors.put("priceNet", ErrorCodes.FIELD_INVALID_VALUE);
        }
        String currency = in.currency() == null ? null : in.currency().strip().toUpperCase(java.util.Locale.ROOT);
        if (currency == null || currency.isEmpty()) {
            errors.put("currency", ErrorCodes.FIELD_REQUIRED);
        } else if (!currency.matches("[A-Z]{3}")) {
            errors.put("currency", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        if (in.validOn() == null) {
            errors.put("validOn", ErrorCodes.FIELD_REQUIRED);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        FuelPrice p = prices.findByCountryAndProductAndValidOn(country, product, in.validOn())
                .orElseGet(FuelPrice::new);
        p.setCountry(country);
        p.setProduct(product);
        p.setValidOn(in.validOn());
        p.setPriceNet(in.priceNet().setScale(4, RoundingMode.HALF_UP));
        p.setCurrency(currency);
        p.setSource(InputFields.blankToNull(in.source()));
        return CalcDto.FuelPriceDto.of(prices.save(p));
    }

    @Transactional
    public void delete(Long id) {
        requireOperator();
        FuelPrice p = prices.findById(id).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        prices.delete(p);
    }

    private static void requireOperator() {
        AuthenticatedUser user = CurrentUser.require();
        if (user.role() != Role.SUPERUSER) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED);
        }
    }
}
