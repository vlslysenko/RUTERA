package com.rutera.core.bootstrap;

import com.rutera.common.domain.AppUser;
import com.rutera.common.domain.Firma;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.repository.FirmaRepository;
import com.rutera.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Standalone first start: when the database has no firma, creates the firma and its owner account from
 * {@link BootstrapProperties}. With no password configured a random one is generated and written to the log
 * once; the owner is expected to change it after the first sign-in.
 */
@Component
@EnableConfigurationProperties(BootstrapProperties.class)
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 16;

    private final BootstrapProperties properties;
    private final FirmaRepository firmas;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public BootstrapRunner(BootstrapProperties properties, FirmaRepository firmas, AppUserRepository users,
                           PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.firmas = firmas;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        if (firmas.count() > 0) {
            return;
        }
        if (users.existsByLogin(properties.adminLogin())) {
            log.warn("Bootstrap skipped: user '{}' already exists although there is no firma", properties.adminLogin());
            return;
        }
        String language = SupportedLanguages.normalize(properties.language());

        Firma firma = new Firma();
        firma.setName(properties.firmaName());
        firma.setShortCode("MAIN");
        firma.setCountry(properties.firmaCountry());
        firma.setLang(language);
        firma = firmas.save(firma);

        boolean generated = properties.adminPassword() == null || properties.adminPassword().isBlank();
        String password = generated ? generatePassword() : properties.adminPassword();

        AppUser owner = new AppUser();
        owner.setFirmaId(firma.getId());
        owner.setLogin(properties.adminLogin());
        owner.setPasswordHash(passwordEncoder.encode(password));
        owner.setRole(Role.OWNER);
        String[] name = splitName(properties.adminName());
        owner.setFirstName(name[0]);
        owner.setLastName(name[1]);
        owner.setLang(language);
        owner.setActive(true);
        owner = users.save(owner);

        log.info("First start: firma '{}' (id {}) and owner '{}' (id {}) created", firma.getName(), firma.getId(),
                owner.getLogin(), owner.getId());
        if (generated) {
            log.warn("First start: no RUTERA_BOOTSTRAP_ADMIN_PASSWORD set. Owner '{}' got the generated password: {}"
                    + "  -- shown once, change it after the first sign-in", owner.getLogin(), password);
        }
    }

    static String[] splitName(String fullName) {
        String trimmed = fullName.trim();
        int space = trimmed.indexOf(' ');
        if (space < 0) {
            return new String[] {trimmed, ""};
        }
        return new String[] {trimmed.substring(0, space).trim(), trimmed.substring(space + 1).trim()};
    }

    private static String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
}
