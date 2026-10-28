package com.rutera.common.domain;

import com.rutera.common.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Application user (SMDP Operator without MD5, RFID, LDAP, Windows and Azure logins).
 * <p>
 * Deliberately not a {@link TenantEntity}: login looks the user up by name before the firma is known,
 * and the service superuser has no firma at all. Services that list users must filter by firma themselves
 * (covered by a test).
 */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    /** NULL only for the service superuser. */
    @Column(name = "firma_id")
    private Long firmaId;

    /** Globally unique, stored lower-case (login = e-mail). */
    @Column(name = "login", nullable = false, length = 254)
    private String login;

    /** Delegating encoder format, e.g. {bcrypt}$2a$12$...; NULL until an invited user sets a password. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 32)
    private Role role;

    @Column(name = "custom_role_id")
    private Long customRoleId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "lang", nullable = false, length = 5)
    private String lang = "en";

    @Column(name = "totp_secret_enc", length = 255)
    private String totpSecretEnc;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    /** UI settings as JSON: start screen, table layouts. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings")
    private String settings;

    public Long getFirmaId() {
        return firmaId;
    }

    public void setFirmaId(Long firmaId) {
        this.firmaId = firmaId;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Long getCustomRoleId() {
        return customRoleId;
    }

    public void setCustomRoleId(Long customRoleId) {
        this.customRoleId = customRoleId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public String getTotpSecretEnc() {
        return totpSecretEnc;
    }

    public void setTotpSecretEnc(String totpSecretEnc) {
        this.totpSecretEnc = totpSecretEnc;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public void setTotpEnabled(boolean totpEnabled) {
        this.totpEnabled = totpEnabled;
    }

    public int getFailedLogins() {
        return failedLogins;
    }

    public void setFailedLogins(int failedLogins) {
        this.failedLogins = failedLogins;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(String settings) {
        this.settings = settings;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public String getFullName() {
        return (firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName);
    }
}
