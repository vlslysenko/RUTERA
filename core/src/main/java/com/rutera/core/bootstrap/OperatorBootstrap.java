package com.rutera.core.bootstrap;

import com.rutera.common.domain.AppUser;
import com.rutera.common.repository.AppUserRepository;
import com.rutera.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * The operator account of a cloud installation: when {@code rutera.bootstrap.operator-login}
 * (RUTERA_BOOTSTRAP_OPERATOR_LOGIN) is set and no user has that login yet, a SUPERUSER without a firma is created at
 * start, with {@code operator-password} or a generated password written to the log once. Empty login (the default):
 * nothing happens, so development and standalone installations have no operator.
 */
@Component
@Order(15)
public class OperatorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperatorBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String login;
    private final String password;

    public OperatorBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder,
                             @Value("${rutera.bootstrap.operator-login:}") String login,
                             @Value("${rutera.bootstrap.operator-password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.login = login.strip().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (login.isEmpty() || users.existsByLogin(login)) {
            return;
        }
        boolean generated = password == null || password.isBlank();
        String secret = generated ? BootstrapRunner.generatePassword() : password;
        AppUser operator = new AppUser();
        operator.setFirmaId(null);
        operator.setLogin(login);
        operator.setPasswordHash(passwordEncoder.encode(secret));
        operator.setRole(Role.SUPERUSER);
        operator.setFirstName("Operator");
        operator.setLastName("");
        operator.setLang("en");
        operator.setActive(true);
        operator = users.save(operator);
        log.info("Operator account '{}' (id {}) created", operator.getLogin(), operator.getId());
        if (generated) {
            log.warn("No RUTERA_BOOTSTRAP_OPERATOR_PASSWORD set. Operator '{}' got the generated password: {}"
                    + "  -- shown once, change it after the first sign-in", operator.getLogin(), secret);
        }
    }
}
