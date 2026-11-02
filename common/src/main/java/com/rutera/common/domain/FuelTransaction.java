package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A fuelling or another card operation (toll, wash, service). {@code (provider, externalId)} is unique in the
 * firma, so importing a file again only finds duplicates. {@link #getAt()} is the station's local time read in
 * the firma's time zone.
 */
@Entity
@Table(name = "fuel_transaction")
public class FuelTransaction extends TenantEntity {

    public enum Product {
        DIESEL, ADBLUE, PETROL, LNG, CNG, TOLL, SERVICE, OTHER
    }

    public enum Unit {
        L, KG, PCS
    }

    public enum Match {
        CARD, PLATE, MANUAL, NONE
    }

    public enum Source {
        IMPORT, MANUAL
    }

    @Column(name = "at", nullable = false)
    private Instant at;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "provider", nullable = false, length = 16)
    private FuelProvider provider;

    @Column(name = "external_id", nullable = false, length = 80)
    private String externalId;

    @Column(name = "card_id")
    private Long cardId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "station", length = 200)
    private String station;

    @Column(name = "country", length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "product", nullable = false, length = 16)
    private Product product;

    @Column(name = "product_raw", length = 100)
    private String productRaw;

    @Column(name = "quantity", precision = 12, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "unit", length = 8)
    private Unit unit;

    @Column(name = "unit_price", precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "net", precision = 14, scale = 2)
    private BigDecimal net;

    @Column(name = "vat", precision = 14, scale = 2)
    private BigDecimal vat;

    @Column(name = "gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal gross;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "odometer_km")
    private Integer odometerKm;

    @Column(name = "plate_raw", length = 20)
    private String plateRaw;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "match_kind", nullable = false, length = 8)
    private Match matchKind = Match.NONE;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 16)
    private Source source = Source.IMPORT;

    @Column(name = "import_id")
    private Long importId;

    @Column(name = "notes", length = 500)
    private String notes;

    public Instant getAt() {
        return at;
    }

    public void setAt(Instant at) {
        this.at = at;
    }

    public FuelProvider getProvider() {
        return provider;
    }

    public void setProvider(FuelProvider provider) {
        this.provider = provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public Long getCardId() {
        return cardId;
    }

    public void setCardId(Long cardId) {
        this.cardId = cardId;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public String getStation() {
        return station;
    }

    public void setStation(String station) {
        this.station = station;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public String getProductRaw() {
        return productRaw;
    }

    public void setProductRaw(String productRaw) {
        this.productRaw = productRaw;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public Unit getUnit() {
        return unit;
    }

    public void setUnit(Unit unit) {
        this.unit = unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getNet() {
        return net;
    }

    public void setNet(BigDecimal net) {
        this.net = net;
    }

    public BigDecimal getVat() {
        return vat;
    }

    public void setVat(BigDecimal vat) {
        this.vat = vat;
    }

    public BigDecimal getGross() {
        return gross;
    }

    public void setGross(BigDecimal gross) {
        this.gross = gross;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Integer getOdometerKm() {
        return odometerKm;
    }

    public void setOdometerKm(Integer odometerKm) {
        this.odometerKm = odometerKm;
    }

    public String getPlateRaw() {
        return plateRaw;
    }

    public void setPlateRaw(String plateRaw) {
        this.plateRaw = plateRaw;
    }

    public Match getMatchKind() {
        return matchKind;
    }

    public void setMatchKind(Match matchKind) {
        this.matchKind = matchKind;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Long getImportId() {
        return importId;
    }

    public void setImportId(Long importId) {
        this.importId = importId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
