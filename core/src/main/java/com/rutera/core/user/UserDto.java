package com.rutera.core.user;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * The {@code User} object of the frontend contract: {@code {id, login, name, role, firma{id,name,timezone}, language,
 * startScreen, privileges, driver{id,name}}}; {@code driver} is the driver linked to this login (driver cabinet). {@code firma} is {@code null} only for the service superuser, whose
 * {@code privileges} list is empty because the superuser bypasses privilege checks.
 */
public record UserDto(
        Long id,
        String login,
        String name,
        Role role,
        FirmaDto firma,
        String language,
        String startScreen,
        List<String> privileges,
        DriverRef driver) {

    public record DriverRef(Long id, String name) {
    }

    /** {@code timezone}: the firma's IANA zone (e.g. Europe/Warsaw); days and weeks are cut in it. */
    public record FirmaDto(Long id, String name, String timezone) {
        public static FirmaDto of(Firma firma) {
            return firma == null ? null : new FirmaDto(firma.getId(), firma.getName(), firma.getTimezone());
        }
    }

    public static UserDto of(AppUser user, Firma firma, UserSettings settings, Collection<String> privileges,
                             DriverRef driver) {
        return new UserDto(user.getId(), user.getLogin(), user.getFullName().trim(), user.getRole(),
                FirmaDto.of(firma), user.getLang(), settings.startScreen(),
                privileges == null ? List.of() : List.copyOf(new TreeSet<>(privileges)), driver);
    }
}
