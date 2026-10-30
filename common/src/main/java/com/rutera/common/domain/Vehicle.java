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
 * Vehicle or trailer of the fleet (docs/DB_SCHEMA.md, group 2). Trailers are rows of the same table
 * ({@link Kind#isTrailer()}); a tractor's usual trailer is {@link #getDefaultTrailerId()}. The plate is unique
 * among the non-deleted vehicles of the firma (column {@code plate_key}, maintained by MySQL).
 */
@Entity
@Table(name = "vehicle")
public class Vehicle extends TenantEntity {

    public enum Kind {
        TRUCK, TRACTOR, VAN, TRAILER, SEMI_TRAILER;

        /** Trailers have no engine: no tachograph, fuel, consumption, odometer, driver or trailer of their own. */
        public boolean isTrailer() {
            return this == TRAILER || this == SEMI_TRAILER;
        }
    }

    public enum Status {
        ACTIVE, SERVICE, INACTIVE
    }

    public enum EuroClass {
        EURO_3, EURO_4, EURO_5, EURO_6
    }

    public enum FuelType {
        DIESEL, HVO, LNG, CNG, PETROL, ELECTRIC
    }

    public enum TachoGen {
        NONE, GEN1, GEN2
    }

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    @Column(name = "plate", nullable = false, length = 20)
    private String plate;

    @Column(name = "vin", length = 17)
    private String vin;

    @Column(name = "make", length = 60)
    private String make;

    @Column(name = "model", length = 60)
    private String model;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "euro_class", length = 32)
    private EuroClass euroClass;

    @Column(name = "axles")
    private Integer axles;

    @Column(name = "gross_weight_kg")
    private Integer grossWeightKg;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(name = "avg_l_100km", precision = 5, scale = 2)
    private BigDecimal avgL100km;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "fuel_type", length = 32)
    private FuelType fuelType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tacho_gen", nullable = false, length = 32)
    private TachoGen tachoGen = TachoGen.NONE;

    @Column(name = "odometer_km")
    private Integer odometerKm;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.ACTIVE;

    /** Driver of group 3; no foreign key until the driver table exists. */
    @Column(name = "default_driver_id")
    private Long defaultDriverId;

    @Column(name = "default_trailer_id")
    private Long defaultTrailerId;

    /** Set by the tachograph import, read-only for the vehicle form. */
    @Column(name = "last_vu_download_at")
    private Instant lastVuDownloadAt;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getPlate() {
        return plate;
    }

    public void setPlate(String plate) {
        this.plate = plate;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public String getMake() {
        return make;
    }

    public void setMake(String make) {
        this.make = make;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public EuroClass getEuroClass() {
        return euroClass;
    }

    public void setEuroClass(EuroClass euroClass) {
        this.euroClass = euroClass;
    }

    public Integer getAxles() {
        return axles;
    }

    public void setAxles(Integer axles) {
        this.axles = axles;
    }

    public Integer getGrossWeightKg() {
        return grossWeightKg;
    }

    public void setGrossWeightKg(Integer grossWeightKg) {
        this.grossWeightKg = grossWeightKg;
    }

    public Integer getHeightMm() {
        return heightMm;
    }

    public void setHeightMm(Integer heightMm) {
        this.heightMm = heightMm;
    }

    public BigDecimal getAvgL100km() {
        return avgL100km;
    }

    public void setAvgL100km(BigDecimal avgL100km) {
        this.avgL100km = avgL100km;
    }

    public FuelType getFuelType() {
        return fuelType;
    }

    public void setFuelType(FuelType fuelType) {
        this.fuelType = fuelType;
    }

    public TachoGen getTachoGen() {
        return tachoGen;
    }

    public void setTachoGen(TachoGen tachoGen) {
        this.tachoGen = tachoGen;
    }

    public Integer getOdometerKm() {
        return odometerKm;
    }

    public void setOdometerKm(Integer odometerKm) {
        this.odometerKm = odometerKm;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Long getDefaultDriverId() {
        return defaultDriverId;
    }

    public void setDefaultDriverId(Long defaultDriverId) {
        this.defaultDriverId = defaultDriverId;
    }

    public Long getDefaultTrailerId() {
        return defaultTrailerId;
    }

    public void setDefaultTrailerId(Long defaultTrailerId) {
        this.defaultTrailerId = defaultTrailerId;
    }

    public Instant getLastVuDownloadAt() {
        return lastVuDownloadAt;
    }

    public void setLastVuDownloadAt(Instant lastVuDownloadAt) {
        this.lastVuDownloadAt = lastVuDownloadAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
