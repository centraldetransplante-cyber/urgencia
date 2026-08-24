package br.gov.saude.sgpur.repository;

import br.gov.saude.sgpur.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    /**
     * Invalida qualquer token pendente anterior do mesmo usuario antes de
     * gerar um novo - so o link mais recente enviado por e-mail deve
     * funcionar (evita um link antigo, ja "esquecido" pelo usuario, ficar
     * valido em paralelo).
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.usuario.id = :usuarioId")
    void deleteByUsuarioId(@Param("usuarioId") Long usuarioId);

    /**
     * Varredura periodica (ver {@code RateLimitLimpezaScheduler}) que libera
     * as linhas de token ja expirado - nao muda nenhuma semantica (um token
     * expirado ja e rejeitado por {@code PasswordResetService}), so evita a
     * tabela crescer sem limite com tokens nunca mais usados.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.dataExpiracao < :agora")
    int deleteExpiradosAntesDe(@Param("agora") Instant agora);
}
