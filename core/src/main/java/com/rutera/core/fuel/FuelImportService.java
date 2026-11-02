package com.rutera.core.fuel;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.domain.FuelCard;
import com.rutera.common.domain.FuelImport;
import com.rutera.common.domain.FuelMapping;
import com.rutera.common.domain.FuelProvider;
import com.rutera.common.domain.FuelTransaction;
import com.rutera.common.domain.StoredFile;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.repository.FuelCardRepository;
import com.rutera.common.repository.FuelImportRepository;
import com.rutera.common.repository.FuelMappingRepository;
import com.rutera.common.repository.FuelTransactionRepository;
import com.rutera.common.repository.StoredFileRepository;
import com.rutera.common.security.Privileges;
import com.rutera.common.security.RequiresPrivilege;
import com.rutera.core.audit.AuditEvent;
import com.rutera.core.audit.AuditService;
import com.rutera.core.fuel.read.ColumnMapper;
import com.rutera.core.fuel.read.ColumnMapper.Field;
import com.rutera.core.fuel.read.FuelValues;
import com.rutera.core.fuel.read.TableReader;
import com.rutera.core.security.CurrentUser;
import com.rutera.core.storage.FileStorage;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import com.rutera.core.web.InputFields;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Imports of fuel card exports ({@code /app/fuel/imports}) and the column mappings per provider
 * ({@code /app/fuel/mappings}). A file is read ({@link TableReader}), its header row and columns found
 * ({@link ColumnMapper}: the given mapping, else the firma's saved one, else the provider preset and generic names),
 * each data row parsed ({@link FuelRows}) and stored unless its duplicate key exists. Cards unknown so far are
 * created; vehicle, driver and trip are matched by {@link FuelMatching}.
 */
@Service
public class FuelImportService {

    public static final String MODULE = "fuel";
    static final String STORED_KIND = "FUEL";
    static final int MAX_BYTES = 20 * 1024 * 1024;
    static final int MAX_ROWS = 50_000;
    static final int MAX_ERRORS = 100;
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<FuelDto.RowError>> ERRORS = new TypeReference<>() {
    };

    private final FuelImportRepository imports;
    private final FuelTransactionRepository transactions;
    private final FuelCardRepository cards;
    private final FuelMappingRepository mappings;
    private final StoredFileRepository storedFiles;
    private final AppUserRepository users;
    private final FirmaRepository firmas;
    private final FuelMatching matching;
    private final FileStorage storage;
    private final AuditService audit;
    private final ObjectMapper json;

    public FuelImportService(FuelImportRepository imports, FuelTransactionRepository transactions,
                             FuelCardRepository cards, FuelMappingRepository mappings, StoredFileRepository storedFiles,
                             AppUserRepository users, FirmaRepository firmas, FuelMatching matching,
                             FileStorage storage, AuditService audit, ObjectMapper json) {
        this.imports = imports;
        this.transactions = transactions;
        this.cards = cards;
        this.mappings = mappings;
        this.storedFiles = storedFiles;
        this.users = users;
        this.firmas = firmas;
        this.matching = matching;
        this.storage = storage;
        this.audit = audit;
        this.json = json;
    }

    /** What the upload has to say about one file. */
    private record Read(FuelProvider provider, List<List<String>> rows, ColumnMapper.Detection detection,
                        FuelDto.Options options, FuelRows parser) {
    }

    // --- preview and import --------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional(readOnly = true)
    public FuelDto.Preview preview(String fileName, byte[] bytes, String provider, String mapping, String options) {
        Read read = read(fileName, bytes, provider, mapping, options);
        ColumnMapper.Detection d = read.detection();
        int headerRow = d == null ? 0 : d.headerRow();
        List<String> headers = d == null ? (read.rows().isEmpty() ? List.of() : read.rows().getFirst()) : d.headers();
        Map<Field, Integer> columns = d == null ? Map.of() : d.columns();
        List<FuelDto.PreviewRow> sample = new ArrayList<>();
        if (d != null) {
            for (int r = headerRow + 1; r < read.rows().size() && sample.size() < 10; r++) {
                FuelRows.Parsed p = read.parser().parse(read.rows().get(r), r + 1);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("at", p.at());
                values.put(Field.CARD_NUMBER.key, p.card().isEmpty() ? null : FuelValues.maskCard(p.card()));
                values.put(Field.PLATE.key, p.plate());
                values.put(Field.TRANSACTION_ID.key, p.transactionId());
                values.put(Field.STATION.key, p.station());
                values.put(Field.COUNTRY.key, p.country());
                values.put(Field.PRODUCT.key, p.productRaw() == null ? null : p.product());
                values.put("productRaw", p.productRaw());
                values.put(Field.QUANTITY.key, p.quantity());
                values.put(Field.UNIT.key, p.unit());
                values.put(Field.UNIT_PRICE.key, p.unitPrice());
                values.put(Field.NET.key, p.net());
                values.put(Field.VAT.key, p.vat());
                values.put(Field.GROSS.key, p.gross());
                values.put(Field.CURRENCY.key, p.currency());
                values.put(Field.ODOMETER.key, p.odometerKm());
                sample.add(new FuelDto.PreviewRow(p.row(), values, p.errors()));
            }
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (Field f : Field.values()) {
            Integer c = columns.get(f);
            found.put(f.key, c == null || c >= headers.size() ? null : headers.get(c));
        }
        return new FuelDto.Preview(read.provider(), headerRow + 1, headers, found, FuelRows.missing(columns),
                read.options(), Math.max(0, read.rows().size() - headerRow - 1), sample);
    }

    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public FuelDto.Import importFile(String fileName, byte[] bytes, String provider, String mapping, String options,
                                     boolean saveMapping) {
        Long firmaId = InputFields.requireFirmaId();
        Read read = read(fileName, bytes, provider, mapping, options);
        ColumnMapper.Detection d = read.detection();
        List<String> missing = FuelRows.missing(d == null ? Map.of() : d.columns());
        if (!missing.isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            errors.put("file", ErrorCodes.FUEL_COLUMNS_MISSING);
            for (String field : missing) {
                errors.put("columns." + field, ErrorCodes.FIELD_REQUIRED);
            }
            throw ApiException.validation(errors);
        }
        String name = FuelRows.truncate(fileName == null || fileName.isBlank() ? "fuel-export" : fileName, 255);
        StoredFile stored = storage.store(firmaId, STORED_KIND, name, "application/octet-stream", bytes,
                FuelRows.sha256Bytes(bytes), CurrentUser.require().id());

        List<FuelRows.Parsed> parsed = new ArrayList<>();
        List<FuelDto.RowError> rowErrors = new ArrayList<>();
        int rowsError = 0;
        for (int r = d.headerRow() + 1; r < read.rows().size(); r++) {
            FuelRows.Parsed p = read.parser().parse(read.rows().get(r), r + 1);
            if (p.errors().isEmpty()) {
                parsed.add(p);
            } else {
                rowsError++;
                for (Map.Entry<String, String> e : p.errors().entrySet()) {
                    if (rowErrors.size() < MAX_ERRORS) {
                        rowErrors.add(new FuelDto.RowError(p.row(), e.getKey(), e.getValue()));
                    }
                }
            }
        }

        FuelImport imp = new FuelImport();
        imp.setFirmaId(firmaId);
        imp.setProvider(read.provider());
        imp.setFileId(stored.getId());
        imp.setFileName(name);
        imp.setRowsTotal(parsed.size() + rowsError);
        imp.setRowsError(rowsError);
        imp.setStatus(FuelImport.Status.DONE);
        imp.setMapping(json.writeValueAsString(names(d.headers(), d.columns())));
        imp.setErrors(json.writeValueAsString(rowErrors));
        imp.setImportedBy(CurrentUser.require().id());
        imp = imports.save(imp);

        Set<String> known = new HashSet<>();
        List<String> keys = parsed.stream().map(FuelRows.Parsed::externalId).toList();
        for (int i = 0; i < keys.size(); i += 500) {
            known.addAll(transactions.existingExternalIds(read.provider(), keys.subList(i, Math.min(keys.size(),
                    i + 500))));
        }
        FuelMatching.Context ctx = matching.context(parsed.stream().map(FuelRows.Parsed::at).toList());
        Map<String, FuelCard> cardOf = new HashMap<>();
        for (FuelCard c : cards.findByProvider(read.provider())) {
            cardOf.put(c.getCardHash(), c);
        }
        LocalDate from = null;
        LocalDate to = null;
        int rowsNew = 0;
        int rowsDup = 0;
        List<FuelTransaction> batch = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            FuelRows.Parsed p = parsed.get(i);
            String key = keys.get(i);
            if (!known.add(key)) {
                rowsDup++;
                continue;
            }
            LocalDate day = p.local().toLocalDate();
            from = from == null || day.isBefore(from) ? day : from;
            to = to == null || day.isAfter(to) ? day : to;
            FuelCard card = p.card().isEmpty() ? null : cardOf.computeIfAbsent(cardHash(read.provider(), p.card()),
                    hash -> newCard(firmaId, read.provider(), p, hash, ctx));
            FuelTransaction t = new FuelTransaction();
            t.setFirmaId(firmaId);
            t.setAt(p.at());
            t.setProvider(read.provider());
            t.setExternalId(key);
            t.setCardId(card == null ? null : card.getId());
            t.setStation(p.station());
            t.setCountry(p.country());
            t.setProduct(p.product());
            t.setProductRaw(p.productRaw());
            t.setQuantity(p.quantity());
            t.setUnit(p.unit());
            t.setUnitPrice(p.unitPrice());
            t.setNet(p.net());
            t.setVat(p.vat());
            t.setGross(p.gross());
            t.setCurrency(p.currency());
            t.setOdometerKm(p.odometerKm());
            t.setPlateRaw(p.plate());
            t.setSource(FuelTransaction.Source.IMPORT);
            t.setImportId(imp.getId());
            ctx.assign(t, card, p.plateKey());
            batch.add(t);
            rowsNew++;
            if (batch.size() == 500) {
                transactions.saveAll(batch);
                batch.clear();
            }
        }
        transactions.saveAll(batch);
        imp.setRowsNew(rowsNew);
        imp.setRowsDup(rowsDup);
        imp.setPeriodFrom(from);
        imp.setPeriodTo(to);
        imports.save(imp);
        if (saveMapping) {
            saveMapping(read.provider(), names(d.headers(), d.columns()), read.options());
        }
        audit.record(AuditEvent.of(MODULE, "fuel_imported", "Imported fuel export " + name)
                .entity("fuel_import", imp.getId()).detail("provider", read.provider().name())
                .detail("rowsNew", rowsNew).detail("rowsDup", rowsDup).detail("rowsError", rowsError));
        return toDto(imp, null);
    }

    private FuelCard newCard(Long firmaId, FuelProvider provider, FuelRows.Parsed p, String hash,
                             FuelMatching.Context ctx) {
        FuelCard c = new FuelCard();
        c.setFirmaId(firmaId);
        c.setProvider(provider);
        c.setCardNumberMasked(FuelValues.maskCard(p.card()));
        c.setCardHash(hash);
        c.setVehicleId(ctx.vehicleByPlate(p.plateKey()));
        return cards.save(c);
    }

    static String cardHash(FuelProvider provider, String normalizedNumber) {
        return FuelRows.sha256(provider.name() + ":" + normalizedNumber);
    }

    private Read read(String fileName, byte[] bytes, String providerName, String mappingJson, String optionsJson) {
        Map<String, String> errors = new LinkedHashMap<>();
        FuelProvider given = InputFields.parseEnum(FuelProvider.class, "provider", providerName, false, errors);
        Map<String, String> explicit = null;
        FuelDto.Options options = null;
        try {
            if (mappingJson != null && !mappingJson.isBlank()) {
                explicit = json.readValue(mappingJson, STRING_MAP);
            }
            if (optionsJson != null && !optionsJson.isBlank()) {
                options = json.readValue(optionsJson, FuelDto.Options.class);
            }
        } catch (RuntimeException e) {
            errors.put(explicit == null ? "mapping" : "options", ErrorCodes.FIELD_INVALID_FORMAT);
        }
        if (bytes == null || bytes.length == 0) {
            errors.put("file", ErrorCodes.FIELD_REQUIRED);
        } else if (bytes.length > MAX_BYTES) {
            errors.put("file", ErrorCodes.FUEL_TOO_LARGE);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        List<List<String>> rows;
        try {
            rows = TableReader.read(fileName, bytes, MAX_ROWS + 20);
        } catch (TableReader.UnreadableException e) {
            throw ApiException.validation(Map.of("file", ErrorCodes.FUEL_UNREADABLE));
        }
        FuelProvider provider = given != null ? given : ColumnMapper.detectProvider(fileName, rows);
        FuelMapping saved = mappings.findByProvider(provider).orElse(null);
        if (explicit == null && saved != null) {
            explicit = json.readValue(saved.getColumnsJson(), STRING_MAP);
        }
        if (options == null && saved != null && saved.getOptionsJson() != null) {
            options = json.readValue(saved.getOptionsJson(), FuelDto.Options.class);
        }
        options = options == null ? new FuelDto.Options(null, null, null) : options;
        Firma firma = firmas.findById(InputFields.requireFirmaId()).orElseThrow();
        ColumnMapper.Detection detection = ColumnMapper.detect(rows, provider, explicit);
        FuelRows parser = detection == null ? null
                : new FuelRows(detection, options, zone(firma), firma.getBaseCurrency());
        return new Read(provider, rows, detection, options, parser);
    }

    // --- import history ------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.FUEL_TRANSACTION_VIEW)
    @Transactional(readOnly = true)
    public FuelDto.ImportPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw ApiException.validation(Map.of(page < 0 ? "page" : "size", ErrorCodes.FIELD_INVALID_VALUE));
        }
        var result = imports.findAllByOrderByIdDesc(PageRequest.of(page, size));
        Map<Long, AppUser> userOf = new HashMap<>();
        for (AppUser u : users.findAllById(result.getContent().stream().map(FuelImport::getImportedBy)
                .filter(Objects::nonNull).toList())) {
            userOf.put(u.getId(), u);
        }
        return new FuelDto.ImportPage(result.getContent().stream()
                .map(i -> toDto(i, userOf.get(i.getImportedBy()))).toList(), result.getTotalElements());
    }

    @RequiresPrivilege(Privileges.FUEL_TRANSACTION_VIEW)
    @Transactional(readOnly = true)
    public FuelDto.Import get(Long id) {
        FuelImport i = load(id);
        return toDto(i, i.getImportedBy() == null ? null : users.findById(i.getImportedBy()).orElse(null));
    }

    /** Undoes an import: its transactions and the stored file go. Cards created by it stay. */
    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public void delete(Long id) {
        FuelImport i = load(id);
        List<FuelTransaction> list = transactions.findByImportId(i.getId());
        transactions.deleteAll(list);
        transactions.flush();
        Long fileId = i.getFileId();
        imports.delete(i);
        imports.flush();
        if (fileId != null) {
            storedFiles.findById(fileId).ifPresent(storage::delete);
        }
        audit.record(AuditEvent.of(MODULE, "fuel_import_deleted", "Deleted fuel import " + i.getFileName())
                .entity("fuel_import", i.getId()).detail("transactions", list.size()));
    }

    private FuelImport load(Long id) {
        Long firmaId = InputFields.requireFirmaId();
        return imports.findById(id).filter(i -> firmaId.equals(i.getFirmaId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND));
    }

    private FuelDto.Import toDto(FuelImport i, AppUser by) {
        List<FuelDto.RowError> errors = i.getErrors() == null ? List.of() : json.readValue(i.getErrors(), ERRORS);
        Map<String, String> mapping = i.getMapping() == null ? Map.of() : json.readValue(i.getMapping(), STRING_MAP);
        return new FuelDto.Import(i.getId(), i.getProvider(), i.getFileName(), i.getPeriodFrom(), i.getPeriodTo(),
                i.getRowsTotal(), i.getRowsNew(), i.getRowsDup(), i.getRowsError(), i.getStatus(), errors, mapping,
                by == null ? null : new FuelDto.Ref(by.getId(), (nz(by.getFirstName()) + " " + nz(by.getLastName()))
                        .strip()), i.getCreatedAt());
    }

    // --- saved mappings ------------------------------------------------------------------------------------

    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional(readOnly = true)
    public FuelDto.Mapping mapping(String providerName) {
        FuelProvider provider = provider(providerName);
        return mappings.findByProvider(provider).map(this::toDto)
                .orElse(new FuelDto.Mapping(provider, Map.of(), new FuelDto.Options(null, null, null)));
    }

    @RequiresPrivilege(Privileges.FUEL_IMPORT_MANAGE)
    @Transactional
    public FuelDto.Mapping updateMapping(String providerName, FuelDto.MappingInput input) {
        FuelProvider provider = provider(providerName);
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> columns = new LinkedHashMap<>();
        if (input.columns() != null) {
            for (Map.Entry<String, String> e : input.columns().entrySet()) {
                if (Field.of(e.getKey()) == null) {
                    errors.put("columns." + e.getKey(), ErrorCodes.FIELD_INVALID_VALUE);
                } else if (e.getValue() != null && !e.getValue().isBlank()) {
                    columns.put(e.getKey(), FuelRows.truncate(e.getValue().strip(), 200));
                }
            }
        }
        FuelDto.Options options = input.options();
        if (options != null && options.currency() != null && FuelValues.currency(options.currency()) == null) {
            errors.put("options.currency", ErrorCodes.FIELD_INVALID_VALUE);
        }
        if (options != null && options.dateFormat() != null && !options.dateFormat().isBlank()) {
            try {
                DateTimeFormatter.ofPattern(options.dateFormat());
            } catch (IllegalArgumentException e) {
                errors.put("options.dateFormat", ErrorCodes.FIELD_INVALID_FORMAT);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return toDto(saveMapping(provider, columns, options));
    }

    private FuelMapping saveMapping(FuelProvider provider, Map<String, String> columns, FuelDto.Options options) {
        FuelMapping m = mappings.findByProvider(provider).orElseGet(FuelMapping::new);
        m.setFirmaId(InputFields.requireFirmaId());
        m.setProvider(provider);
        m.setColumnsJson(json.writeValueAsString(columns));
        FuelDto.Options normalized = options == null ? null : new FuelDto.Options(
                options.dateFormat() == null || options.dateFormat().isBlank() ? null : options.dateFormat().strip(),
                options.decimalComma(),
                options.currency() == null ? null : FuelValues.currency(options.currency()));
        m.setOptionsJson(normalized == null ? null : json.writeValueAsString(normalized));
        return mappings.save(m);
    }

    private FuelDto.Mapping toDto(FuelMapping m) {
        return new FuelDto.Mapping(m.getProvider(), json.readValue(m.getColumnsJson(), STRING_MAP),
                m.getOptionsJson() == null ? new FuelDto.Options(null, null, null)
                        : json.readValue(m.getOptionsJson(), FuelDto.Options.class));
    }

    private static FuelProvider provider(String name) {
        try {
            return FuelProvider.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND);
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** field key -> header text of the found columns. */
    private static Map<String, String> names(List<String> headers, Map<Field, Integer> columns) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Field f : Field.values()) {
            Integer c = columns.get(f);
            if (c != null && c < headers.size()) {
                result.put(f.key, headers.get(c));
            }
        }
        return result;
    }

    static ZoneId zone(Firma firma) {
        try {
            return ZoneId.of(firma.getTimezone());
        } catch (DateTimeException | NullPointerException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
