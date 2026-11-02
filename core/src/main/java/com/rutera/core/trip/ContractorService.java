package com.rutera.core.trip;

import com.rutera.common.domain.Contractor;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.ContractorRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Contractors of the firma ({@code /app/contractors}): customers, subcontracted carriers, suppliers. Soft delete;
 * the VAT id is stored upper-case without spaces, hyphens and dots and is unique among the non-deleted contractors.
 */
@Service
public class ContractorService {

    public static final String MODULE = "trip";

    private static final Pattern VAT_SHAPE = Pattern.compile("[A-Z0-9]{2,32}");
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final ContractorRepository contractors;
    private final AuditService audit;
    private final ObjectMapper json;

    public ContractorService(ContractorRepository contractors, AuditService audit, ObjectMapper json) {
        this.contractors = contractors;
        this.audit = audit;
        this.json = json;
    }

    /** Non-deleted contractors by name; {@code q} matches name and VAT id, {@code role} is CUSTOMER or CARRIER. */
    @RequiresPrivilege({Privileges.TRIP_ORDER_VIEW, Privileges.TRIP_CONTRACTOR_MANAGE})
    @Transactional(readOnly = true)
    public List<ContractorDto> list(String q, String role) {
        InputFields.requireFirmaId();
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        String r = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        return contractors.findByDeletedFalseOrderByNameAscIdAsc().stream()
                .filter(c -> !r.equals("CUSTOMER") || c.isCustomer())
                .filter(c -> !r.equals("CARRIER") || c.isCarrier())
                .filter(c -> needle == null || c.getName().toLowerCase(Locale.ROOT).contains(needle)
                        || (c.getVatId() != null && c.getVatId().toLowerCase(Locale.ROOT).contains(needle)))
                .map(this::toDto)
                .toList();
    }

    @RequiresPrivilege({Privileges.TRIP_ORDER_VIEW, Privileges.TRIP_CONTRACTOR_MANAGE})
    @Transactional(readOnly = true)
    public ContractorDto get(Long id) {
        return toDto(load(id));
    }

    @RequiresPrivilege(Privileges.TRIP_CONTRACTOR_MANAGE)
    @Transactional
    public ContractorDto create(ContractorDto.Input input) {
        Contractor c = new Contractor();
        c.setFirmaId(InputFields.requireFirmaId());
        apply(c, input);
        c = save(c);
        audit.record(AuditEvent.of(MODULE, "contractor_created", "Added contractor " + c.getName())
                .entity("contractor", c.getId()));
        return toDto(c);
    }

    @RequiresPrivilege(Privileges.TRIP_CONTRACTOR_MANAGE)
    @Transactional
    public ContractorDto update(Long id, ContractorDto.Input input) {
        Contractor c = load(id);
        apply(c, input);
        c = save(c);
        audit.record(AuditEvent.of(MODULE, "contractor_updated", "Updated contractor " + c.getName())
                .entity("contractor", c.getId()));
        return toDto(c);
    }

    /** Soft delete: orders and invoices keep pointing at the contractor; the VAT id becomes free. */
    @RequiresPrivilege(Privileges.TRIP_CONTRACTOR_MANAGE)
    @Transactional
    public void delete(Long id) {
        Contractor c = load(id);
        c.setDeleted(true);
        contractors.save(c);
        audit.record(AuditEvent.of(MODULE, "contractor_deleted", "Deleted contractor " + c.getName())
                .entity("contractor", c.getId()));
    }

    /** A non-deleted contractor of the firma, for orders; null when the id does not name one. */
    Contractor findUsable(Long id) {
        if (id == null) {
            return null;
        }
        Long firmaId = InputFields.requireFirmaId();
        return contractors.findByIdAndDeletedFalse(id).filter(c -> firmaId.equals(c.getFirmaId())).orElse(null);
    }

    private Contractor load(Long id) {
        Contractor c = findUsable(id);
        if (c == null) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND);
        }
        return c;
    }

    private ContractorDto toDto(Contractor c) {
        return ContractorDto.of(c, c.getInvoiceEmails() == null ? List.of() : json.readValue(c.getInvoiceEmails(), STRINGS));
    }

    private void apply(Contractor c, ContractorDto.Input in) {
        Map<String, String> errors = new LinkedHashMap<>();
        String country = InputFields.country("country", in.country(), errors);
        String vat = InputFields.blankToNull(in.vatId());
        if (vat != null) {
            vat = vat.toUpperCase(Locale.ROOT).replaceAll("[\\s.-]+", "");
            if (!VAT_SHAPE.matcher(vat).matches()) {
                errors.put("vatId", ErrorCodes.FIELD_INVALID_FORMAT);
            } else {
                String key = vat;
                if (contractors.findByVatIdAndDeletedFalse(key).stream().anyMatch(o -> !o.getId().equals(c.getId()))) {
                    errors.put("vatId", ErrorCodes.CONTRACTOR_VAT_TAKEN);
                }
            }
        }
        List<String> emails = new ArrayList<>();
        if (in.invoiceEmails() != null) {
            for (String raw : in.invoiceEmails()) {
                String email = InputFields.blankToNull(raw);
                if (email == null) {
                    continue;
                }
                if (!EMAIL_SHAPE.matcher(email).matches()) {
                    errors.put("invoiceEmails", ErrorCodes.FIELD_INVALID_FORMAT);
                } else if (!emails.contains(email)) {
                    emails.add(email);
                }
            }
        }
        String lang = InputFields.blankToNull(in.lang());
        if (lang != null) {
            lang = lang.toLowerCase(Locale.ROOT);
            if (!SupportedLanguages.isSupported(lang)) {
                errors.put("lang", ErrorCodes.FIELD_INVALID_VALUE);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        c.setName(in.name().strip());
        c.setVatId(vat);
        c.setCountry(country);
        c.setAddress(InputFields.blankToNull(in.address()));
        c.setPhone(InputFields.blankToNull(in.phone()));
        c.setInvoiceEmails(emails.isEmpty() ? null : json.writeValueAsString(emails));
        c.setPaymentDays(in.paymentDays());
        c.setLang(lang);
        c.setCustomer(!Objects.equals(in.isCustomer(), Boolean.FALSE));
        c.setCarrier(Objects.equals(in.isCarrier(), Boolean.TRUE));
        c.setNotes(InputFields.blankToNull(in.notes()));
    }

    /** The unique key on (firma_id, vat_key) is the last word on duplicates, e.g. two saves at the same time. */
    private Contractor save(Contractor c) {
        try {
            return contractors.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.validation(Map.of("vatId", ErrorCodes.CONTRACTOR_VAT_TAKEN));
        }
    }
}
