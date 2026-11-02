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
import java.time.LocalDate;

/**
 * A CMR consignment note (boxes 1-24 of the form). Parties, goods lines, successive carriers and signatures are
 * JSON; the issued PDF is {@link #getFileId()}.
 */
@Entity
@Table(name = "cmr_document")
public class CmrDocument extends TenantEntity {

    public enum Status {
        DRAFT, ISSUED, CANCELLED
    }

    public enum Source {
        GENERATED, UPLOADED
    }

    public enum PaymentTerms {
        PAID, FORWARD
    }

    @Column(name = "number", nullable = false, length = 32)
    private String number;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 16)
    private Source source = Source.GENERATED;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "trip_id")
    private Long tripId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sender")
    private String sender;

    @Column(name = "sender_contractor_id")
    private Long senderContractorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consignee")
    private String consignee;

    @Column(name = "consignee_contractor_id")
    private Long consigneeContractorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "carrier")
    private String carrier;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "successive_carriers")
    private String successiveCarriers;

    @Column(name = "delivery_place", length = 200)
    private String deliveryPlace;

    @Column(name = "delivery_country", length = 2)
    private String deliveryCountry;

    @Column(name = "loading_place", length = 200)
    private String loadingPlace;

    @Column(name = "loading_country", length = 2)
    private String loadingCountry;

    @Column(name = "loading_date")
    private LocalDate loadingDate;

    @Column(name = "documents_attached", length = 500)
    private String documentsAttached;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "goods")
    private String goods;

    @Column(name = "sender_instructions", length = 1000)
    private String senderInstructions;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "payment_terms", length = 16)
    private PaymentTerms paymentTerms;

    @Column(name = "payment_instructions", length = 500)
    private String paymentInstructions;

    @Column(name = "cod_amount", precision = 14, scale = 2)
    private BigDecimal codAmount;

    @Column(name = "cod_currency", length = 3)
    private String codCurrency;

    @Column(name = "carrier_reservations", length = 1000)
    private String carrierReservations;

    @Column(name = "special_agreements", length = 1000)
    private String specialAgreements;

    @Column(name = "established_place", length = 100)
    private String establishedPlace;

    @Column(name = "established_date")
    private LocalDate establishedDate;

    @Column(name = "vehicle_plates", length = 64)
    private String vehiclePlates;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signatures")
    private String signatures;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "issued_by")
    private Long issuedBy;

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getTripId() {
        return tripId;
    }

    public void setTripId(Long tripId) {
        this.tripId = tripId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public Long getSenderContractorId() {
        return senderContractorId;
    }

    public void setSenderContractorId(Long senderContractorId) {
        this.senderContractorId = senderContractorId;
    }

    public String getConsignee() {
        return consignee;
    }

    public void setConsignee(String consignee) {
        this.consignee = consignee;
    }

    public Long getConsigneeContractorId() {
        return consigneeContractorId;
    }

    public void setConsigneeContractorId(Long consigneeContractorId) {
        this.consigneeContractorId = consigneeContractorId;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getSuccessiveCarriers() {
        return successiveCarriers;
    }

    public void setSuccessiveCarriers(String successiveCarriers) {
        this.successiveCarriers = successiveCarriers;
    }

    public String getDeliveryPlace() {
        return deliveryPlace;
    }

    public void setDeliveryPlace(String deliveryPlace) {
        this.deliveryPlace = deliveryPlace;
    }

    public String getDeliveryCountry() {
        return deliveryCountry;
    }

    public void setDeliveryCountry(String deliveryCountry) {
        this.deliveryCountry = deliveryCountry;
    }

    public String getLoadingPlace() {
        return loadingPlace;
    }

    public void setLoadingPlace(String loadingPlace) {
        this.loadingPlace = loadingPlace;
    }

    public String getLoadingCountry() {
        return loadingCountry;
    }

    public void setLoadingCountry(String loadingCountry) {
        this.loadingCountry = loadingCountry;
    }

    public LocalDate getLoadingDate() {
        return loadingDate;
    }

    public void setLoadingDate(LocalDate loadingDate) {
        this.loadingDate = loadingDate;
    }

    public String getDocumentsAttached() {
        return documentsAttached;
    }

    public void setDocumentsAttached(String documentsAttached) {
        this.documentsAttached = documentsAttached;
    }

    public String getGoods() {
        return goods;
    }

    public void setGoods(String goods) {
        this.goods = goods;
    }

    public String getSenderInstructions() {
        return senderInstructions;
    }

    public void setSenderInstructions(String senderInstructions) {
        this.senderInstructions = senderInstructions;
    }

    public PaymentTerms getPaymentTerms() {
        return paymentTerms;
    }

    public void setPaymentTerms(PaymentTerms paymentTerms) {
        this.paymentTerms = paymentTerms;
    }

    public String getPaymentInstructions() {
        return paymentInstructions;
    }

    public void setPaymentInstructions(String paymentInstructions) {
        this.paymentInstructions = paymentInstructions;
    }

    public BigDecimal getCodAmount() {
        return codAmount;
    }

    public void setCodAmount(BigDecimal codAmount) {
        this.codAmount = codAmount;
    }

    public String getCodCurrency() {
        return codCurrency;
    }

    public void setCodCurrency(String codCurrency) {
        this.codCurrency = codCurrency;
    }

    public String getCarrierReservations() {
        return carrierReservations;
    }

    public void setCarrierReservations(String carrierReservations) {
        this.carrierReservations = carrierReservations;
    }

    public String getSpecialAgreements() {
        return specialAgreements;
    }

    public void setSpecialAgreements(String specialAgreements) {
        this.specialAgreements = specialAgreements;
    }

    public String getEstablishedPlace() {
        return establishedPlace;
    }

    public void setEstablishedPlace(String establishedPlace) {
        this.establishedPlace = establishedPlace;
    }

    public LocalDate getEstablishedDate() {
        return establishedDate;
    }

    public void setEstablishedDate(LocalDate establishedDate) {
        this.establishedDate = establishedDate;
    }

    public String getVehiclePlates() {
        return vehiclePlates;
    }

    public void setVehiclePlates(String vehiclePlates) {
        this.vehiclePlates = vehiclePlates;
    }

    public String getSignatures() {
        return signatures;
    }

    public void setSignatures(String signatures) {
        this.signatures = signatures;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Long getIssuedBy() {
        return issuedBy;
    }

    public void setIssuedBy(Long issuedBy) {
        this.issuedBy = issuedBy;
    }
}
