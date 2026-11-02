package com.rutera.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The firma's plan of payment reminders: on which days relative to the due date a reminder goes out
 * ({@link #getSteps()}, JSON array of {@code {offsetDays, escalate}}), at which hour, with which copies.
 */
@Entity
@Table(name = "reminder_schedule")
public class ReminderSchedule extends TenantEntity {

    @Column(name = "name", nullable = false, length = 100)
    private String name = "Default";

    @Column(name = "is_default", nullable = false)
    private boolean defaultSchedule = true;

    /** Hour of the day (firma time zone) when the daily run queues the reminders. */
    @Column(name = "send_hour", nullable = false)
    private int sendHour = 9;

    /** JSON array of addresses copied on every reminder. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cc_emails")
    private String ccEmails;

    @Column(name = "stop_when_paid", nullable = false)
    private boolean stopWhenPaid = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "steps", nullable = false)
    private String steps;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isDefaultSchedule() {
        return defaultSchedule;
    }

    public void setDefaultSchedule(boolean defaultSchedule) {
        this.defaultSchedule = defaultSchedule;
    }

    public int getSendHour() {
        return sendHour;
    }

    public void setSendHour(int sendHour) {
        this.sendHour = sendHour;
    }

    public String getCcEmails() {
        return ccEmails;
    }

    public void setCcEmails(String ccEmails) {
        this.ccEmails = ccEmails;
    }

    public boolean isStopWhenPaid() {
        return stopWhenPaid;
    }

    public void setStopWhenPaid(boolean stopWhenPaid) {
        this.stopWhenPaid = stopWhenPaid;
    }

    public String getSteps() {
        return steps;
    }

    public void setSteps(String steps) {
        this.steps = steps;
    }
}
