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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The shared reference fuel prices ({@code /app/calc/fuel-prices}, table {@code fuel_price_ref}): everyone with the
 * calculator reads them, only the operator (SUPERUSER) writes, because they are the same for all firmas. A row per
 * country, product, source and day; net without VAT is required, gross with VAT and the VAT rate fill each other.
 */
@Service
public class FuelPriceService {

    static final Set<FuelTransaction.Product> PRODUCTS = EnumSet.of(FuelTransaction.Product.DIESEL,
            FuelTransaction.Product.ADBLUE, FuelTransaction.Product.PETROL, FuelTransaction.Product.LNG,
            FuelTransaction.Product.CNG);
    static final String MANUAL_SOURCE = "MANUAL";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final FuelPriceRepository prices;

    public FuelPriceService(FuelPriceRepository prices) {
        this.prices = prices;
    }

    @RequiresPrivilege(Privileges.TRIP_TRIP_VIEW)
    @Transactional(readOnly = true)
    public List<CalcDto.FuelPriceDto> list() {
        return prices.findAllByOrderByCountryAscProductAscPriceDateDescIdDesc().stream()
                .map(CalcDto.FuelPriceDto::of).toList();
    }

    /** Creates the row of the country, product, source and day, or replaces its prices. */
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
        FuelPrice.Kind kind = in.kind() == null || in.kind().isBlank() ? FuelPrice.Kind.AVERAGE
                : InputFields.parseEnum(FuelPrice.Kind.class, "kind", in.kind(), false, errors);
        positive("net", in.net(), errors);
        positive("gross", in.gross(), errors);
        if (in.vatRate() != null && (in.vatRate().signum() < 0 || in.vatRate().compareTo(HUNDRED) >= 0)) {
            errors.put("vatRate", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (in.net() == null && (in.gross() == null || in.vatRate() == null)) {
            errors.put("net", ErrorCodes.FIELD_REQUIRED);
        }
        String currency = in.currency() == null ? null : in.currency().strip().toUpperCase(Locale.ROOT);
        if (currency == null || currency.isEmpty()) {
            errors.put("currency", ErrorCodes.FIELD_REQUIRED);
        } else if (!currency.matches("[A-Z]{3}")) {
            errors.put("currency", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        if (in.priceDate() == null) {
            errors.put("priceDate", ErrorCodes.FIELD_REQUIRED);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        String source = InputFields.blankToNull(in.source()) == null ? MANUAL_SOURCE : in.source().strip();
        BigDecimal net = in.net() != null ? in.net()
                : in.gross().multiply(HUNDRED).divide(HUNDRED.add(in.vatRate()), 4, RoundingMode.HALF_UP);
        BigDecimal gross = in.gross() != null ? in.gross() : in.vatRate() == null ? null
                : net.multiply(HUNDRED.add(in.vatRate())).divide(HUNDRED, 4, RoundingMode.HALF_UP);
        FuelPrice p = prices.findByCountryAndProductAndSourceAndPriceDate(country, product, source, in.priceDate())
                .orElseGet(FuelPrice::new);
        p.setCountry(country);
        p.setProduct(product);
        p.setPriceDate(in.priceDate());
        p.setNet(net.setScale(4, RoundingMode.HALF_UP));
        p.setGross(gross == null ? null : gross.setScale(4, RoundingMode.HALF_UP));
        p.setVatRate(in.vatRate());
        p.setExcisePer1000l(in.excisePer1000l());
        p.setCurrency(currency);
        p.setKind(kind);
        p.setSource(source);
        return CalcDto.FuelPriceDto.of(prices.save(p));
    }

    @Transactional
    public void delete(Long id) {
        requireOperator();
        FuelPrice p = prices.findById(id).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
        prices.delete(p);
    }

    private static void positive(String field, BigDecimal value, Map<String, String> errors) {
        if (value != null && (value.signum() <= 0 || value.compareTo(new BigDecimal("9999999")) > 0)) {
            errors.put(field, ErrorCodes.FIELD_INVALID_VALUE);
        }
    }

    private static void requireOperator() {
        AuthenticatedUser user = CurrentUser.require();
        if (user.role() != Role.SUPERUSER) {
            throw ApiException.forbidden(ErrorCodes.ACCESS_DENIED);
        }
    }
}
