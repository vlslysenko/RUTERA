package com.rutera.core.user;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.security.Role;

/**
 * The {@code User} object of the frontend contract: {@code {id, login, name, role, firma{id,name}, language,
 * startScreen}}. {@code firma} is {@code null} only for the service superuser.
 */
public record UserDto(
        Long id,
        String login,
        String name,
        Role role,
        FirmaDto firma,
        String language,
        String startScreen) {

    public record FirmaDto(Long id, String name) {
        public static FirmaDto of(Firma firma) {
            return firma == null ? null : new FirmaDto(firma.getId(), firma.getName());
        }
    }

    public static UserDto of(AppUser user, Firma firma, UserSettings settings) {
        return new UserDto(user.getId(), user.getLogin(), user.getFullName().trim(), user.getRole(),
                FirmaDto.of(firma), user.getLang(), settings.startScreen());
    }
}
