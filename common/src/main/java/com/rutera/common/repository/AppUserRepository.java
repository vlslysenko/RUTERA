package com.rutera.common.repository;

import com.rutera.common.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code app_user} has no automatic tenant filter (see {@link AppUser}); every method that lists users
 * takes the firma explicitly.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByLoginAndDeletedFalse(String login);

    boolean existsByLogin(String login);

    List<AppUser> findByFirmaIdAndDeletedFalseOrderByLastNameAscFirstNameAsc(Long firmaId);

    long countByFirmaIdAndDeletedFalse(Long firmaId);

    @Modifying
    @Query("update AppUser u set u.failedLogins = u.failedLogins + 1 where u.id = :id")
    int incrementFailedLogins(@Param("id") Long id);

    @Modifying
    @Query("update AppUser u set u.failedLogins = 0, u.lockedUntil = :until where u.id = :id")
    int lock(@Param("id") Long id, @Param("until") Instant until);

    @Modifying
    @Query("update AppUser u set u.failedLogins = 0, u.lockedUntil = null where u.id = :id")
    int resetFailedLogins(@Param("id") Long id);
}
