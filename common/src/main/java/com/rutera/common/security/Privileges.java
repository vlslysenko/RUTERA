package com.rutera.common.security;

import java.util.List;

/**
 * Catalogue of privilege keys: {@code module.entity/action}, flat strings as in SMDP but in English and
 * grouped by RUTERA module. {@code manage} means create, update and delete. Keys are checked with
 * {@code @RequiresPrivilege} on the service or controller method, never by hand in each controller.
 * <p>
 * New keys are added here and to the role defaults in {@link RolePrivileges}; the frontend receives the
 * effective map of the signed-in user and hides what is not allowed.
 */
public final class Privileges {

    // Administration of the firma
    public static final String ADMIN_USER_VIEW = "admin.user/view";
    public static final String ADMIN_USER_MANAGE = "admin.user/manage";
    public static final String ADMIN_ROLE_MANAGE = "admin.role/manage";
    public static final String ADMIN_FIRMA_MANAGE = "admin.firma/manage";
    public static final String ADMIN_AUDIT_VIEW = "admin.audit/view";
    public static final String ADMIN_API_TOKEN_MANAGE = "admin.api_token/manage";

    // Fleet
    public static final String FLEET_VEHICLE_VIEW = "fleet.vehicle/view";
    public static final String FLEET_VEHICLE_MANAGE = "fleet.vehicle/manage";
    public static final String FLEET_DOCUMENT_MANAGE = "fleet.document/manage";

    // Map and positions
    public static final String MAP_POSITION_VIEW = "map.position/view";

    // Drivers and HR
    public static final String DRIVER_VIEW = "driver.driver/view";
    public static final String DRIVER_MANAGE = "driver.driver/manage";
    public static final String DRIVER_ABSENCE_MANAGE = "driver.absence/manage";
    public static final String DRIVER_PAYMENT_VIEW = "driver.payment/view";
    public static final String DRIVER_PAYMENT_MANAGE = "driver.payment/manage";

    // Tachograph and regulation 561/2006
    public static final String TACHO_FILE_UPLOAD = "tacho.file/upload";
    public static final String TACHO_HOS_VIEW = "tacho.hos/view";
    public static final String TACHO_VIOLATION_ACK = "tacho.violation/ack";

    // Orders, trips, contractors, CMR
    public static final String TRIP_ORDER_VIEW = "trip.order/view";
    public static final String TRIP_ORDER_MANAGE = "trip.order/manage";
    public static final String TRIP_TRIP_VIEW = "trip.trip/view";
    public static final String TRIP_TRIP_MANAGE = "trip.trip/manage";
    public static final String TRIP_CONTRACTOR_MANAGE = "trip.contractor/manage";
    public static final String TRIP_CMR_UPLOAD = "trip.cmr/upload";
    public static final String TRIP_CMR_MANAGE = "trip.cmr/manage";

    // Invoices, payments, reminders
    public static final String FINANCE_INVOICE_VIEW = "finance.invoice/view";
    public static final String FINANCE_INVOICE_MANAGE = "finance.invoice/manage";
    public static final String FINANCE_PAYMENT_MANAGE = "finance.payment/manage";
    public static final String FINANCE_REMINDER_MANAGE = "finance.reminder/manage";

    // Fuel
    public static final String FUEL_TRANSACTION_VIEW = "fuel.transaction/view";
    public static final String FUEL_IMPORT_MANAGE = "fuel.import/manage";

    // Reports
    public static final String REPORT_VIEW = "report.report/view";

    // Driver cabinet (PWA): the driver's own data only
    public static final String DRIVER_APP_SELF_USE = "driver_app.self/use";

    // Own profile (language, start screen, password)
    public static final String PROFILE_SELF_MANAGE = "profile.self/manage";

    /** Every known key, in display order. */
    public static final List<String> ALL = List.of(
            ADMIN_USER_VIEW, ADMIN_USER_MANAGE, ADMIN_ROLE_MANAGE, ADMIN_FIRMA_MANAGE, ADMIN_AUDIT_VIEW,
            ADMIN_API_TOKEN_MANAGE,
            FLEET_VEHICLE_VIEW, FLEET_VEHICLE_MANAGE, FLEET_DOCUMENT_MANAGE,
            MAP_POSITION_VIEW,
            DRIVER_VIEW, DRIVER_MANAGE, DRIVER_ABSENCE_MANAGE, DRIVER_PAYMENT_VIEW, DRIVER_PAYMENT_MANAGE,
            TACHO_FILE_UPLOAD, TACHO_HOS_VIEW, TACHO_VIOLATION_ACK,
            TRIP_ORDER_VIEW, TRIP_ORDER_MANAGE, TRIP_TRIP_VIEW, TRIP_TRIP_MANAGE, TRIP_CONTRACTOR_MANAGE,
            TRIP_CMR_UPLOAD, TRIP_CMR_MANAGE,
            FINANCE_INVOICE_VIEW, FINANCE_INVOICE_MANAGE, FINANCE_PAYMENT_MANAGE, FINANCE_REMINDER_MANAGE,
            FUEL_TRANSACTION_VIEW, FUEL_IMPORT_MANAGE,
            REPORT_VIEW,
            DRIVER_APP_SELF_USE,
            PROFILE_SELF_MANAGE);

    private Privileges() {
    }

    public static boolean isKnown(String key) {
        return ALL.contains(key);
    }
}
